package main

import (
	"errors"
	"io"
	"os"
)

// encryptedFile:在 .lvault 容器上提供随机访问读取,
// 实现 io.ReaderAt + io.ReadSeeker,供 http.ServeContent 做 Range / 视频拖动。
// 解密按需进行:ReadAt 只解涉及到的块,不从头解整个文件。
// 见 docs/VAULT_ENCRYPTION.md §5。

type encryptedFile struct {
	f         *os.File
	dataStart int64
	cek       []byte
	plainSize int64
	blockSize int
	pos       int64 // 顺序读取位置(Read/Seek 使用)
}

var _ io.ReaderAt = (*encryptedFile)(nil)
var _ io.ReadSeeker = (*encryptedFile)(nil)

func openEncryptedFile(path string, mk []byte) (*encryptedFile, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	h, dataStart, err := readLvaultHeader(f)
	if err != nil {
		_ = f.Close()
		return nil, err
	}
	cek, err := unwrapCEK(mk, h.WrappedCEK, h.CekNonce)
	if err != nil {
		_ = f.Close()
		return nil, err
	}
	return &encryptedFile{f: f, dataStart: dataStart, cek: cek, plainSize: h.PlainSize, blockSize: h.BlockSize}, nil
}

func (e *encryptedFile) PlainSize() int64 { return e.plainSize }

func (e *encryptedFile) Close() error { return e.f.Close() }

// Read 支持顺序读取(ServeContent 用),内部记录位置。
func (e *encryptedFile) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if e.pos >= e.plainSize {
		return 0, io.EOF
	}
	n, err := e.ReadAt(p, e.pos)
	e.pos += int64(n)
	return n, err
}

func (e *encryptedFile) ReadAt(p []byte, off int64) (int, error) {
	if off < 0 {
		return 0, errors.New("保险箱:负偏移")
	}
	if off >= e.plainSize {
		if len(p) == 0 {
			return 0, nil
		}
		return 0, io.EOF
	}
	avail := e.plainSize - off
	if int64(len(p)) > avail {
		p = p[:avail]
	}
	total := 0
	for len(p) > 0 {
		blockIndex := uint64(off / int64(e.blockSize))
		inBlock := off % int64(e.blockSize)
		thisBlockPlain := blockPlainLen(blockIndex, e.plainSize, e.blockSize)
		plain, err := e.readBlock(blockIndex, thisBlockPlain)
		if err != nil {
			return total, err
		}
		start := inBlock
		n := int64(len(p))
		if n > thisBlockPlain-start {
			n = thisBlockPlain - start
		}
		copy(p[:n], plain[start:start+n])
		p = p[n:]
		off += n
		total += int(n)
	}
	return total, nil
}

func (e *encryptedFile) readBlock(index uint64, plainLen int64) ([]byte, error) {
	off := blockOffset(e.dataStart, index, e.plainSize, e.blockSize)
	encodedLen := lvaultNonceSize + int(plainLen) + lvaultTagSize
	buf := make([]byte, encodedLen)
	if _, err := e.f.ReadAt(buf, off); err != nil {
		return nil, err
	}
	return decryptBlock(e.cek, index, buf)
}

// Seek 实现 io.Seeker(相对明文流,语义与 os.File 一致)。
func (e *encryptedFile) Seek(offset int64, whence int) (int64, error) {
	var abs int64
	switch whence {
	case io.SeekStart:
		abs = offset
	case io.SeekCurrent:
		abs = e.pos + offset
	case io.SeekEnd:
		abs = e.plainSize + offset
	default:
		return 0, errors.New("保险箱:不支持的 whence")
	}
	if abs < 0 {
		return 0, errors.New("保险箱:负偏移")
	}
	e.pos = abs
	return abs, nil
}

// writeEncryptedToFile 加密写入:保留函数名供容器单测使用(不带文件名)。
func writeEncryptedToFile(dstPath string, src io.Reader, mk []byte) (int64, error) {
	return writeEncryptedStreamNamed(dstPath, src, mk, "")
}

// writeEncryptedStream 把明文 src 流式加密写入 dstPath(不加密文件名)。
func writeEncryptedStream(dstPath string, src io.Reader, mk []byte) (int64, error) {
	return writeEncryptedStreamNamed(dstPath, src, mk, "")
}

// writeEncryptedStreamNamed 把明文 src 流式加密写入 dstPath,返回明文字节数。
// displayName 非空时把显示名加密后写进 header(磁盘文件名是 token,不含显示名)。
//
// 明文只在内存里停留一个块(64KB),磁盘上只出现密文;头部区先占位、
// 数据写完后再回填,因此不需要把整份密文缓冲在内存,也不需要二次拷贝。
// 出错时会删掉半成品文件,避免保险箱里留下无法解密的残片。
func writeEncryptedStreamNamed(dstPath string, src io.Reader, mk []byte, displayName string) (int64, error) {
	if err := ensureCEKLen(mk); err != nil {
		return 0, err
	}
	cek, err := newCEK()
	if err != nil {
		return 0, err
	}
	wrapped, cekNonce, err := wrapCEK(mk, cek)
	if err != nil {
		return 0, err
	}
	var nameEnc, nameNonce string
	if displayName != "" {
		nameEnc, nameNonce, err = sealVaultName(mk, displayName)
		if err != nil {
			return 0, err
		}
	}
	f, err := os.OpenFile(dstPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return 0, err
	}
	committed := false
	defer func() {
		_ = f.Close()
		if !committed {
			_ = os.Remove(dstPath)
		}
	}()

	// 预留固定头部区,数据从 lvaultHeaderReserve 开始写。
	if _, err := f.Seek(lvaultHeaderReserve, io.SeekStart); err != nil {
		return 0, err
	}
	var plain [lvaultBlockSize]byte
	var total int64
	var index uint64
	for {
		n, rerr := io.ReadFull(src, plain[:])
		if n > 0 {
			encoded, eerr := encryptBlock(cek, index, plain[:n])
			if eerr != nil {
				return 0, eerr
			}
			if _, werr := f.Write(encoded); werr != nil {
				return 0, werr
			}
			total += int64(n)
			index++
		}
		if rerr == io.EOF || rerr == io.ErrUnexpectedEOF {
			break
		}
		if rerr != nil {
			return 0, rerr
		}
	}

	h := lvaultHeader{
		WrappedCEK: wrapped,
		CekNonce:   cekNonce,
		PlainSize:  total,
		BlockSize:  lvaultBlockSize,
		Name:       nameEnc,
		NameNonce:  nameNonce,
	}
	if _, err := writeLvaultHeaderFixed(f, h); err != nil {
		return 0, err
	}
	if err := f.Sync(); err != nil {
		return 0, err
	}
	if err := f.Close(); err != nil {
		return 0, err
	}
	committed = true
	return total, nil
}

func ensureCEKLen(mk []byte) error {
	if len(mk) != 32 {
		return errors.New("保险箱:主密钥长度必须为 32 字节")
	}
	return nil
}
