package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"net/http"
	"time"
)

// ============================================================
//  保险箱密钥管理:信封加密(见 docs/VAULT_ENCRYPTION.md §4)
//
//  vault.json 只保存「被口令派生密钥包裹的随机主密钥 VMK」,
//  磁盘上不存在能直接解密文件的明文密钥:
//
//    口令 + salt + iter --PBKDF2-HMAC-SHA256--> KEK(32B)
//    vault.json:  wrapped(VMK, KEK) + nonce
//    .lvault:     wrapped(CEK, VMK) + nonce(每个文件一个随机 CEK)
//
//  因此「改密码」只需用新 KEK 重新包裹同一个 VMK,不需要重写任何
//  .lvault 文件;VMK 只在解锁会话内存里存在。
//
//  v1 旧格式的 hash 字段就是 PBKDF2 派生值本身(等同明文主密钥),
//  解锁时检测到 v1 会自动升级为 v2 并移除 hash。M1 之前没有任何
//  .lvault 文件,所以升级时直接换一个全新的随机 VMK(旧派生值随之作废)。
// ============================================================

const (
	vaultKeyVersion = 2
	// vaultKDFIter 是新配置的 PBKDF2 迭代次数(与旧版保持一致的量级)。
	vaultKDFIter = 120000
	// 由 VMK 派生的子密钥用途标签(不同用途用不同 label,互不通用)。
	vaultLabelNameEnc = "linNas-vault-name-enc-v1"
	vaultLabelNameTok = "linNas-vault-name-token-v1"
)

var (
	// errVaultPassword 表示口令校验失败。
	errVaultPassword = errors.New("保险箱:密码不正确")
	// errVaultKeyCfg 表示 vault.json 的密钥字段损坏/缺字段。
	errVaultKeyCfg = errors.New("保险箱:密钥配置损坏")
)

// zeroKeyBytes 尽力清零内存里的密钥,减少驻留时间(Go 无法保证编译器不复制)。
func zeroKeyBytes(b []byte) {
	for i := range b {
		b[i] = 0
	}
}

// vaultKEK 由口令 + salt 派生 32 字节密钥加密密钥。
func vaultKEK(password, salt string, iter int) []byte {
	if iter <= 0 {
		iter = vaultKDFIter
	}
	return pbkdf2Key([]byte(password), []byte(salt), iter, 32)
}

// vaultSubkey 用 HMAC-SHA256(VMK, label) 派生用途隔离的子密钥。
// 文件名加密键与文件名 token 键都用它派生,均不直接复用 VMK。
func vaultSubkey(mk []byte, label string) []byte {
	h := hmac.New(sha256.New, mk)
	h.Write([]byte(label))
	return h.Sum(nil)
}

// newVaultConfigFor 用口令派生的 KEK 包裹 vmk,生成 v2 配置(每次新 salt)。
func newVaultConfigFor(vmk []byte, password string) (*vaultConfig, error) {
	salt := randHex(16)
	iter := vaultKDFIter
	kek := vaultKEK(password, salt, iter)
	wrapped, nonce, err := wrapCEK(kek, vmk)
	zeroKeyBytes(kek)
	if err != nil {
		return nil, err
	}
	return &vaultConfig{
		Version:    vaultKeyVersion,
		Salt:       salt,
		Iter:       iter,
		WrappedKey: wrapped,
		KeyNonce:   nonce,
	}, nil
}

// newVaultConfig 生成全新随机 VMK 的 v2 配置(首次创建保险箱)。
func newVaultConfig(password string) (*vaultConfig, error) {
	vmk, err := newCEK()
	if err != nil {
		return nil, err
	}
	cfg, err := newVaultConfigFor(vmk, password)
	zeroKeyBytes(vmk)
	return cfg, err
}

// unlockVaultKey 校验口令并返回保险箱主密钥 VMK。
// next != nil 表示读到的是 v1 旧配置,调用方应把 next 写回磁盘完成升级。
func (vc *vaultConfig) unlockVaultKey(password string) (vmk []byte, next *vaultConfig, err error) {
	if vc == nil || vc.Salt == "" {
		return nil, nil, errVaultKeyCfg
	}
	kek := vaultKEK(password, vc.Salt, vc.Iter)
	defer zeroKeyBytes(kek)
	if vc.WrappedKey != "" {
		key, uerr := unwrapCEK(kek, vc.WrappedKey, vc.KeyNonce)
		if uerr != nil {
			return nil, nil, errVaultPassword
		}
		return key, nil, nil
	}
	// v1 遗留:hash 即 PBKDF2 派生值(明文主密钥),校验通过后立刻升级。
	want, herr := hex.DecodeString(vc.Hash)
	if herr != nil || len(want) != 32 {
		return nil, nil, errVaultKeyCfg
	}
	if subtle.ConstantTimeCompare(kek, want) != 1 {
		return nil, nil, errVaultPassword
	}
	key, kerr := newCEK()
	if kerr != nil {
		return nil, nil, kerr
	}
	cfg, cerr := newVaultConfigFor(key, password)
	if cerr != nil {
		zeroKeyBytes(key)
		return nil, nil, cerr
	}
	return key, cfg, nil
}

// ---- 解锁会话(token → VMK) ----

// vaultToken 取请求里的解锁 token:查询参数 vt 优先,其次 X-Vault-Token 头。
func vaultToken(r *http.Request) string {
	tok := r.URL.Query().Get("vt")
	if tok == "" {
		tok = r.Header.Get("X-Vault-Token")
	}
	return tok
}

// setVaultSession 记录解锁会话:过期时间 + 该会话的主密钥副本。
func setVaultSession(tok string, exp int64, vmk []byte) {
	vault.mu.Lock()
	defer vault.mu.Unlock()
	if vault.sessions == nil {
		vault.sessions = map[string]int64{}
	}
	if vault.keys == nil {
		vault.keys = map[string][]byte{}
	}
	vault.sessions[tok] = exp
	vault.keys[tok] = append([]byte(nil), vmk...)
}

// vaultKeyForToken 返回 token 对应的 VMK 副本;会话不存在/过期/无密钥时返回 false。
func vaultKeyForToken(tok string) ([]byte, bool) {
	if tok == "" {
		return nil, false
	}
	vault.mu.Lock()
	defer vault.mu.Unlock()
	exp, ok := vault.sessions[tok]
	if !ok {
		return nil, false
	}
	if time.Now().Unix() > exp {
		delete(vault.sessions, tok)
		if key, has := vault.keys[tok]; has {
			zeroKeyBytes(key)
			delete(vault.keys, tok)
		}
		return nil, false
	}
	key, has := vault.keys[tok]
	if !has || len(key) != 32 {
		// 没有密钥的会话不能解密文件,直接作废(服务重启后残留的 token 等)。
		delete(vault.sessions, tok)
		return nil, false
	}
	return append([]byte(nil), key...), true
}

// dropVaultSession 使单个解锁会话失效并清除其密钥。
func dropVaultSession(tok string) {
	if tok == "" {
		return
	}
	vault.mu.Lock()
	defer vault.mu.Unlock()
	delete(vault.sessions, tok)
	if key, ok := vault.keys[tok]; ok {
		zeroKeyBytes(key)
		delete(vault.keys, tok)
	}
}

// clearVaultSessions 使所有解锁会话失效(改密码后调用)。
func clearVaultSessions() {
	vault.mu.Lock()
	defer vault.mu.Unlock()
	for tok, key := range vault.keys {
		zeroKeyBytes(key)
		delete(vault.keys, tok)
	}
	vault.sessions = map[string]int64{}
}
