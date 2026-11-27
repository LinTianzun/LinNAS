package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

// ============================================================
//  保险箱静态加密:.lvault 容器格式(见 docs/VAULT_ENCRYPTION.md §3)
//
//  文件布局:
//    [0..8)    magic  "LVLT1\0\0"
//    [8]       version = 1
//    [9]       flags(预留)
//    [10..12)  reserved
//    [12..16)  headerLen (uint32 LE) —— header JSON 字节数
//    [16..16+headerLen) header(JSON,明文)
//    [16+headerLen..)   data blocks
//
//  每个 data block:
//    gcmNonce(12B) || ciphertext(blockSize) || gcmTag(16B)
//  第 i 块加密的明文 = 原文件第 [i*blockSize,(i+1)*blockSize) 字节;
//  末块明文可短于 blockSize。AAD = magic || blockIndex(uint64 LE),
//  防止块被重排 / 搬移到别的偏移。
// ============================================================

const (
	lvaultMagic   = "LVLT1\x00\x00\x00" // 恰好 8 字节:L V L T 1 \0 \0 \0
	lvaultVersion = 2
	// lvaultVersionLegacy 是 M2 及更早写出的版本(无加密文件名)。
	lvaultVersionLegacy = 1
	// lvaultFixedHeader 是 magic(8)+version(1)+flags(1)+reserved(2)+headerLen(4)。
	lvaultFixedHeader = 16
	// lvaultBlockSize 是每个数据块的明文大小(64KB),随机读放大与块头开销的折中。
	lvaultBlockSize = 64 * 1024
	// lvaultNonceSize / lvaultTagSize 是 GCM 的 nonce 与认证标签长度。
	lvaultNonceSize = 12
	lvaultTagSize   = 16
	// lvaultHeaderReserve 是容器头部区固定预留的总长度(含 16 字节固定头)。
	// 流式加密时先占位写入,全部数据写完后回填 header:这样既不用把整个
	// 密文缓冲在内存,也不用二次拷贝数据;header JSON 不足时用空格补齐。
	lvaultHeaderReserve = 512
)

// lvaultHeader 是容器头部 JSON(明文存储),含解密所需的全部元数据。
type lvaultHeader struct {
	WrappedCEK string `json:"wrappedCEK"` // 用主密钥 AES-GCM 包裹的 32 字节内容密钥(hex)
	CekNonce   string `json:"cekNonce"`   // 包裹 CEK 的 GCM nonce(hex)
	PlainSize  int64  `json:"plainSize"`  // 明文总字节数
	BlockSize  int    `json:"blockSize"`  // 明文块大小
	// Name / NameNonce:用 VMK 派生的名字密钥加密的显示文件名(hex)。
	// 为空 = 旧容器,显示名就是磁盘文件名去掉 .lvault(M2 及更早格式)。
	Name      string `json:"name,omitempty"`
	NameNonce string `json:"nameNonce,omitempty"`
}

// lvaultErrWrap 表示认证失败(密码错误 / 密文被篡改)。
var lvaultErrWrap = errors.New("保险箱:密钥解包失败(密码错误或数据损坏)")

// lvaultErrBlock 表示某个数据块认证失败 / 损坏。
var lvaultErrBlock = errors.New("保险箱:数据块校验失败")

// newCEK 生成随机 32 字节内容密钥。
func newCEK() ([]byte, error) {
	cek := make([]byte, 32)
	if _, err := rand.Read(cek); err != nil {
		return nil, err
	}
	return cek, nil
}

// wrapCEK 用主密钥 mk 通过 AES-GCM 包裹 CEK,返回 (wrappedHex, nonceHex)。
func wrapCEK(mk, cek []byte) (wrappedHex, nonceHex string, err error) {
	gcm, err := newGCM(mk)
	if err != nil {
		return "", "", err
	}
	nonce := make([]byte, lvaultNonceSize)
	if _, err := rand.Read(nonce); err != nil {
		return "", "", err
	}
	sealed := gcm.Seal(nil, nonce, cek, nil)
	return hex.EncodeToString(sealed), hex.EncodeToString(nonce), nil
}

// unwrapCEK 用主密钥 mk 解开 header 里的 wrappedCEK,返回 32 字节 CEK。
// 认证失败返回 lvaultErrWrap。
func unwrapCEK(mk []byte, wrappedHex, nonceHex string) ([]byte, error) {
	wrapped, err := hex.DecodeString(wrappedHex)
	if err != nil {
		return nil, lvaultErrWrap
	}
	nonce, err := hex.DecodeString(nonceHex)
	if err != nil {
		return nil, lvaultErrWrap
	}
	gcm, err := newGCM(mk)
	if err != nil {
		return nil, err
	}
	cek, err := gcm.Open(nil, nonce, wrapped, nil)
	if err != nil {
		return nil, lvaultErrWrap
	}
	if len(cek) != 32 {
		return nil, lvaultErrWrap
	}
	return cek, nil
}

// newGCM 返回以 key 为对称密钥的 GCM(自动走 AES-NI)。
func newGCM(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// blockAAD 返回第 index 块的附加认证数据:magic || index(uint64 LE)。
func blockAAD(index uint64) []byte {
	aad := make([]byte, 0, len(lvaultMagic)+8)
	aad = append(aad, lvaultMagic...)
	var idx [8]byte
	binary.LittleEndian.PutUint64(idx[:], index)
	return append(aad, idx[:]...)
}

// writeLvaultHeaderFixed 把 header 写到 ws 的偏移 0 处,并把头部区补齐到
// lvaultHeaderReserve 字节(JSON 尾部补空格 —— JSON 允许尾随空白)。
// 返回写入的头部区总字节数,即数据区起始偏移。
func writeLvaultHeaderFixed(ws io.WriteSeeker, h lvaultHeader) (int64, error) {
	meta, err := json.Marshal(h)
	if err != nil {
		return 0, err
	}
	metaCap := lvaultHeaderReserve - lvaultFixedHeader
	if len(meta) > metaCap {
		return 0, fmt.Errorf("保险箱:容器头过大(%d 字节,上限 %d)", len(meta), metaCap)
	}
	buf := make([]byte, lvaultHeaderReserve)
	copy(buf, lvaultMagic)
	buf[8] = lvaultVersion
	binary.LittleEndian.PutUint32(buf[12:16], uint32(metaCap))
	copy(buf[lvaultFixedHeader:], meta)
	for i := lvaultFixedHeader + len(meta); i < len(buf); i++ {
		buf[i] = ' '
	}
	if _, err := ws.Seek(0, io.SeekStart); err != nil {
		return 0, err
	}
	n, err := ws.Write(buf)
	return int64(n), err
}

// readLvaultHeader 从 r 开头读出 header,返回 header 与数据区起始偏移。
func readLvaultHeader(r io.Reader) (lvaultHeader, int64, error) {
	var h lvaultHeader
	fixed := make([]byte, lvaultFixedHeader)
	if _, err := io.ReadFull(r, fixed); err != nil {
		return h, 0, fmt.Errorf("读取容器头失败: %w", err)
	}
	if string(fixed[:len(lvaultMagic)]) != lvaultMagic {
		return h, 0, errors.New("不是有效的保险箱容器(magic 不匹配)")
	}
	if v := int(fixed[8]); v != lvaultVersion && v != lvaultVersionLegacy {
		return h, 0, fmt.Errorf("不支持的容器版本: %d", fixed[8])
	}
	headerLen := binary.LittleEndian.Uint32(fixed[12:16])
	if headerLen == 0 || headerLen > 1<<20 {
		return h, 0, errors.New("容器头长度非法")
	}
	meta := make([]byte, headerLen)
	if _, err := io.ReadFull(r, meta); err != nil {
		return h, 0, fmt.Errorf("读取容器元数据失败: %w", err)
	}
	if err := json.Unmarshal(meta, &h); err != nil {
		return h, 0, fmt.Errorf("解析容器元数据失败: %w", err)
	}
	if h.BlockSize <= 0 || h.BlockSize > 1<<20 {
		return h, 0, errors.New("容器块大小非法")
	}
	return h, int64(lvaultFixedHeader) + int64(headerLen), nil
}

// encryptBlock 用 CEK 加密第 index 块明文,返回 gcmNonce||ciphertext||tag。
func encryptBlock(cek []byte, index uint64, plain []byte) ([]byte, error) {
	gcm, err := newGCM(cek)
	if err != nil {
		return nil, err
	}
	nonce := blockNonce(index)
	out := make([]byte, 0, lvaultNonceSize+len(plain)+lvaultTagSize)
	out = append(out, nonce...)
	out = gcm.Seal(out, nonce, plain, blockAAD(index))
	return out, nil
}

// decryptBlock 用 CEK 解密一个已编码块,返回明文。认证失败返回 lvaultErrBlock。
func decryptBlock(cek []byte, index uint64, encoded []byte) ([]byte, error) {
	if len(encoded) < lvaultNonceSize+lvaultTagSize {
		return nil, lvaultErrBlock
	}
	gcm, err := newGCM(cek)
	if err != nil {
		return nil, err
	}
	nonce := encoded[:lvaultNonceSize]
	ct := encoded[lvaultNonceSize:]
	plain, err := gcm.Open(nil, nonce, ct, blockAAD(index))
	if err != nil {
		return nil, lvaultErrBlock
	}
	return plain, nil
}

// blockNonce 由块索引派生确定性 nonce(同文件内不重复;CEK 每文件唯一)。
func blockNonce(index uint64) []byte {
	nonce := make([]byte, lvaultNonceSize)
	binary.LittleEndian.PutUint64(nonce[:8], index)
	// 后 4 字节置 0;nonce 唯一性由 (CEK, index) 保证。
	return nonce
}

// lvaultBlockCount 返回 plainSize 对应的数据块数量(末块可为短块)。
func lvaultBlockCount(plainSize int64, blockSize int) int64 {
	if plainSize <= 0 {
		return 0
	}
	return (plainSize + int64(blockSize) - 1) / int64(blockSize)
}

// blockPlainLen 返回第 index 块的明文长度:满块为 blockSize,末块为剩余字节。
func blockPlainLen(index uint64, plainSize int64, blockSize int) int64 {
	start := int64(index) * int64(blockSize)
	remaining := plainSize - start
	if remaining < int64(blockSize) {
		return remaining
	}
	return int64(blockSize)
}

// blockOffset 返回第 index 块在容器文件里的起始偏移。
// 末块可为短块,其编码长度 = nonce+实际明文+tag 比满块短,
// 因此必须累加前面各块的「实际」编码长度,不能用固定满块大小 × index。
func blockOffset(dataStart int64, index uint64, plainSize int64, blockSize int) int64 {
	off := dataStart
	for i := uint64(0); i < index; i++ {
		off += lvaultNonceSize + blockPlainLen(i, plainSize, blockSize) + lvaultTagSize
	}
	return off
}

// ---- 文件名加密(v2) ----
//
// 容器文件名是确定性 token(见 vault_paths.go),显示名加密后存在 header 里,
// 这样资源管理器既看不到显示名、也看不到真实文件名。名字密钥由 VMK 派生,
// 与 CEK 包裹键相互独立(不同 label 的 HMAC-SHA256)。

// vaultNameAAD 是加密显示名时使用的附加认证数据,与块数据的域分开。
func vaultNameAAD() []byte {
	aad := make([]byte, 0, len(lvaultMagic)+4)
	aad = append(aad, lvaultMagic...)
	return append(aad, 'n', 'a', 'm', 'e')
}

// sealVaultName 用 VMK 派生的名字密钥加密显示名,返回 (密文hex, nonce hex)。
func sealVaultName(mk []byte, name string) (string, string, error) {
	gcm, err := newGCM(vaultSubkey(mk, vaultLabelNameEnc))
	if err != nil {
		return "", "", err
	}
	nonce := make([]byte, lvaultNonceSize)
	if _, err := rand.Read(nonce); err != nil {
		return "", "", err
	}
	sealed := gcm.Seal(nil, nonce, []byte(name), vaultNameAAD())
	return hex.EncodeToString(sealed), hex.EncodeToString(nonce), nil
}

// openVaultName 解密 header 里的显示名;失败返回错误(不返回垃圾)。
func openVaultName(mk []byte, encHex, nonceHex string) (string, error) {
	sealed, err := hex.DecodeString(encHex)
	if err != nil {
		return "", errors.New("保险箱:文件名密文损坏")
	}
	nonce, err := hex.DecodeString(nonceHex)
	if err != nil || len(nonce) != lvaultNonceSize {
		return "", errors.New("保险箱:文件名 nonce 损坏")
	}
	gcm, err := newGCM(vaultSubkey(mk, vaultLabelNameEnc))
	if err != nil {
		return "", err
	}
	plain, err := gcm.Open(nil, nonce, sealed, vaultNameAAD())
	if err != nil {
		return "", errors.New("保险箱:文件名解密失败(密钥不匹配或数据损坏)")
	}
	return string(plain), nil
}
