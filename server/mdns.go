package main

import (
	"fmt"
	"log"
	"net"
	"os"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// mDNS 服务类型:手机通过 NsdManager 搜索该类型即可发现本机。
const mdnsServiceType = "_linnas._tcp."

// mdnsAdvertiser 负责在局域网广播本服务(_linnas._tcp.local)。
type mdnsAdvertiser struct {
	conn *net.UDPConn
	stop chan struct{}
}

// startMDNS 开始广播。失败只记录日志,不影响主功能(手机端还有子网扫描兜底)。
func startMDNS(port int, instanceName string) *mdnsAdvertiser {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		log.Printf("mDNS 广播启动失败(不影响使用): %v", err)
		return nil
	}
	adv := &mdnsAdvertiser{conn: conn, stop: make(chan struct{})}

	host, err := os.Hostname()
	if err != nil || strings.TrimSpace(host) == "" {
		host = "linNas"
	}
	host = strings.ReplaceAll(host, ".", "-")
	instance := fmt.Sprintf("%s-%s", instanceName, host)
	if len(instance) > 60 {
		instance = instance[:60]
	}

	go adv.listen()
	go adv.announceLoop(instance, port, instanceName)
	return adv
}

// Close stops advertising.
func (m *mdnsAdvertiser) Close() {
	if m == nil {
		return
	}
	close(m.stop)
	_ = m.conn.Close()
}

func (m *mdnsAdvertiser) announceLoop(instance string, port int, instanceName string) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		m.announce(instance, port, instanceName)
		select {
		case <-m.stop:
			return
		case <-ticker.C:
		}
	}
}

// announce 发送一次 A / PTR / SRV / TXT 记录。
func (m *mdnsAdvertiser) announce(instance string, port int, instanceName string) {
	hostname, _ := os.Hostname()
	hostname = strings.ReplaceAll(hostname, ".", "-")
	fullInstance := instance + "." + mdnsServiceType
	target := fullInstance + "local."

	var addrs []net.IP
	if ifs, err := net.InterfaceAddrs(); err == nil {
		for _, a := range ifs {
			if ipnet, ok := a.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
				if v4 := ipnet.IP.To4(); v4 != nil {
					addrs = append(addrs, v4)
				}
			}
		}
	}
	if len(addrs) == 0 {
		return
	}

	for _, ip := range addrs {
		m.send(dnsmessage.Resource{
			Header: dnsmessage.ResourceHeader{
				Name:  dnsmessage.MustNewName(hostname + ".local."),
				Type:  dnsmessage.TypeA,
				Class: dnsmessage.ClassINET,
				TTL:   120,
			},
			Body: &dnsmessage.AResource{
				A: [4]byte{ip[0], ip[1], ip[2], ip[3]},
			},
		})
	}

	srvName := dnsmessage.MustNewName(target)
	m.send(dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{
			Name:  dnsmessage.MustNewName(mdnsServiceType),
			Type:  dnsmessage.TypePTR,
			Class: dnsmessage.ClassINET,
			TTL:   120,
		},
		Body: &dnsmessage.PTRResource{PTR: srvName},
	})
	m.send(dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{
			Name:  srvName,
			Type:  dnsmessage.TypeSRV,
			Class: dnsmessage.ClassINET,
			TTL:   120,
		},
		Body: &dnsmessage.SRVResource{
			Priority: 0,
			Weight:   0,
			Port:     uint16(port),
			Target:   dnsmessage.MustNewName(hostname + ".local."),
		},
	})
	m.send(dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{
			Name:  srvName,
			Type:  dnsmessage.TypeTXT,
			Class: dnsmessage.ClassINET,
			TTL:   120,
		},
		Body: &dnsmessage.TXTResource{
			TXT: []string{"port=" + fmt.Sprint(port), "name=" + instanceName},
		},
	})
}

// broadcastAddrs 返回本机的广播地址列表(全局广播 + 各子网广播)。
func broadcastAddrs() []net.IP {
	out := []net.IP{net.IPv4bcast}
	if ifs, err := net.InterfaceAddrs(); err == nil {
		for _, a := range ifs {
			ipnet, ok := a.(*net.IPNet)
			if !ok || ipnet.IP.IsLoopback() {
				continue
			}
			v4 := ipnet.IP.To4()
			if v4 == nil {
				continue
			}
			mask := ipnet.Mask
			if len(mask) != 4 {
				continue
			}
			b := [4]byte{}
			for i := 0; i < 4; i++ {
				b[i] = v4[i] | ^mask[i]
			}
			out = append(out, net.IPv4(b[0], b[1], b[2], b[3]))
		}
	}
	return out
}

// send 组包并广播(mDNS 组播地址 + UDP 广播地址双通道)。
func (m *mdnsAdvertiser) send(res dnsmessage.Resource) {
	msg := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:               0,
			Response:         true,
			OpCode:           0,
			Authoritative:    true,
			RecursionDesired: false,
		},
		Answers: []dnsmessage.Resource{res},
	}
	buf, err := msg.Pack()
	if err != nil {
		return
	}
	// mDNS 标准组播
	_, _ = m.conn.WriteToUDP(buf, &net.UDPAddr{IP: net.IPv4(224, 0, 0, 251), Port: 5353})
	// UDP 广播(部分设备/网络会丢弃组播,广播更可靠)
	for _, ip := range broadcastAddrs() {
		_, _ = m.conn.WriteToUDP(buf, &net.UDPAddr{IP: ip, Port: 5353})
	}
}

// listen 接收 mDNS 查询并回应。
func (m *mdnsAdvertiser) listen() {
	buf := make([]byte, 1500)
	for {
		select {
		case <-m.stop:
			return
		default:
		}
		_ = m.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		n, _, err := m.conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		var msg dnsmessage.Message
		if err := msg.Unpack(buf[:n]); err != nil {
			continue
		}
		if msg.Header.Response {
			continue
		}
		for _, q := range msg.Questions {
			if q.Type != dnsmessage.TypeA && q.Type != dnsmessage.TypePTR {
				continue
			}
			name := strings.ToLower(q.Name.String())
			if !strings.Contains(name, "linnas") && !strings.Contains(name, "local.") {
				continue
			}
			hostname, _ := os.Hostname()
			hostname = strings.ReplaceAll(hostname, ".", "-")
			if ifs, err := net.InterfaceAddrs(); err == nil {
				for _, a := range ifs {
					ipnet, ok := a.(*net.IPNet)
					if !ok || ipnet.IP.IsLoopback() {
						continue
					}
					v4 := ipnet.IP.To4()
					if v4 == nil {
						continue
					}
					m.send(dnsmessage.Resource{
						Header: dnsmessage.ResourceHeader{
							Name:  dnsmessage.MustNewName(hostname + ".local."),
							Type:  dnsmessage.TypeA,
							Class: dnsmessage.ClassINET,
							TTL:   120,
						},
						Body: &dnsmessage.AResource{
							A: [4]byte{v4[0], v4[1], v4[2], v4[3]},
						},
					})
				}
			}
		}
	}
}
