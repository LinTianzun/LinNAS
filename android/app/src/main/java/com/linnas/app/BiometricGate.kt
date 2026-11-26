package com.linnas.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 生物识别门禁 + 敏感串加密存储。
 *
 * - [canUse] 判断设备是否具备可用生物识别(有硬件且已录入)。
 * - [authenticate] 统一封装 API 28+ 的 android.hardware.biometrics.BiometricPrompt、
 *   API 24-27 的 FingerprintManager,以及两者都不可用时的 KeyguardManager 锁屏凭据确认。
 * - [saveSecret]/[loadSecret]/[clearSecret]/[clearAll] 用 Android Keystore 的 AES/GCM
 *   密钥把敏感串(会话 token、保险箱密码)加密后存入 SharedPreferences。
 *
 * 安全约定:日志不输出敏感内容;密钥失效或解密失败时清理对应密文并视为未启用。
 */
object BiometricGate {

    private const val PREFS = "linnas_secure"
    /** 开关存在普通 prefs("linnas")里:它不是敏感数据,且不能与密文同库(避免误清)。 */
    private const val SWITCH_PREFS = "linnas"
    private const val KEY_ENABLED = "bio_enabled"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "linnas_bio_key"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH = 12

    /**
     * 单次识别失败(手指没对准等)的提示:系统弹窗不会关闭、用户可直接重试。
     * 调用方用常量区分「可重试的失败」与「弹窗已关闭(取消/锁定)」,
     * 后者才适合回退到密码输入,避免每次按歪都弹一个密码框。
     */
    const val MSG_RETRY = "验证失败,请重试"

    /**
     * 设备是否有可用的生物识别(指纹硬件已录入)。
     *
     * 说明:平台类 android.hardware.biometrics.BiometricManager 并不提供静态 from(Context)
     * (那是 AndroidX biometric 的 API),故这里统一用 FingerprintManager 判断指纹硬件与录入;
     * API 28+ 的实际认证仍走平台 BiometricPrompt(见 authenticatePlatform)。
     */
    fun canUse(ctx: Context): Boolean = fingerprintEnrolled(ctx)

    private fun fingerprintEnrolled(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 23) false
        else {
            val fm = ctx.getSystemService(FingerprintManager::class.java) ?: return false
            fm.isHardwareDetected && fm.hasEnrolledFingerprints()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 生物识别总开关(设置页可开关)。默认 true:老用户升级后行为不变。
     * 只置开关、不删 Keystore 密钥与密文——重新打开即可用,也避免把已保存的
     * 会话/保险箱密码一并清掉(那会让用户在重新打开后还得重输一次)。
     */
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(SWITCH_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(SWITCH_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /**
     * 请求生物识别认证。API 28+ 走平台 BiometricPrompt,24-27 走 FingerprintManager,
     * 两者都不可用时降级为 KeyguardManager 的锁屏凭据确认。成功/失败均回调,绝不静默。
     *
     * 总开关关闭时不弹系统验证,直接走 onFail——调用方据此回退到密码/账号路径。
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFail: (String) -> Unit,
    ) {
        if (!isEnabled(activity)) {
            onFail("已在设置中关闭生物识别")
            return
        }
        if (canUse(activity)) {
            if (Build.VERSION.SDK_INT >= 28) {
                authenticatePlatform(activity, title, subtitle, onSuccess, onFail)
            } else {
                authenticateFingerprint(activity, title, subtitle, onSuccess, onFail)
            }
        } else {
            authenticateCredentialFallback(activity, title, subtitle, onSuccess, onFail)
        }
    }

    /** API 28+:平台 android.hardware.biometrics.BiometricPrompt。 */
    private fun authenticatePlatform(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFail: (String) -> Unit,
    ) {
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButton(
                    "取消",
                    executor,
                    DialogInterface.OnClickListener { _, _ -> onFail("已取消") },
                )
                .build()
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFail(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // 单次识别失败不关弹窗,用 MSG_RETRY 让调用方知道「还能在弹窗里重试」。
                    onFail(MSG_RETRY)
                }
            }
            prompt.authenticate(CancellationSignal(), executor, callback)
        } catch (e: Exception) {
            onFail("生物识别不可用:${e.message}")
        }
    }

    /** API 24-27:android.hardware.fingerprint.FingerprintManager。 */
    private fun authenticateFingerprint(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFail: (String) -> Unit,
    ) {
        try {
            val fm = activity.getSystemService(FingerprintManager::class.java)
                ?: run { onFail("设备不支持指纹"); return }
            val callback = object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult?) {
                    onSuccess()
                }

                override fun onAuthenticationError(errMsgId: Int, errString: CharSequence) {
                    onFail(errString.toString())
                }

                override fun onAuthenticationHelp(helpMsgId: Int, helpString: CharSequence) {
                    // 系统提示(如"手指移动")不视为失败。
                }

                override fun onAuthenticationFailed() {
                    // 同上:弹窗仍在,提示重试而不是回退到密码。
                    onFail(MSG_RETRY)
                }
            }
            fm.authenticate(null, CancellationSignal(), 0, callback, null)
        } catch (e: Exception) {
            onFail("指纹验证不可用:${e.message}")
        }
    }

    /** 降级路径:用 KeyguardManager 的锁屏凭据(PIN/图案/密码)确认。 */
    private fun authenticateCredentialFallback(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFail: (String) -> Unit,
    ) {
        val km = activity.getSystemService(KeyguardManager::class.java)
            ?: run { onFail("设备不支持锁屏凭据"); return }
        if (!km.isDeviceSecure) {
            onFail("该设备未设置锁屏密码,无法使用生物识别登录")
            return
        }
        try {
            val intent = km.createConfirmDeviceCredentialIntent(title, subtitle)
                ?: run { onFail("无法发起锁屏确认"); return }
            val tag = "linnas_cred_${System.identityHashCode(activity)}"
            val registry = activity.activityResultRegistry
            val launcher = registry.register(
                tag,
                ActivityResultContracts.StartActivityForResult(),
            ) { r ->
                if (r.resultCode == Activity.RESULT_OK) onSuccess() else onFail("已取消")
            }
            launcher.launch(intent)
        } catch (e: Exception) {
            onFail("无法发起锁屏确认:${e.message}")
        }
    }

    // ---------- 敏感串加密存储 ----------

    /** 加密保存敏感串;失败时清理并返回 false。 */
    fun saveSecret(ctx: Context, key: String, value: String): Boolean = try {
        val cipher = initCipher(ctx, Cipher.ENCRYPT_MODE, null) ?: return false
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(ct, 0, blob, iv.size, ct.size)
        securePrefs(ctx).edit()
            .putString(key, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
        true
    } catch (_: Exception) {
        clearSecret(ctx, key)
        false
    }

    /** 解密读取;密钥失效或解密失败时清理密文并返回 null。 */
    fun loadSecret(ctx: Context, key: String): String? {
        val raw = securePrefs(ctx).getString(key, null) ?: return null
        return try {
            val data = Base64.decode(raw, Base64.NO_WRAP)
            if (data.size <= IV_LENGTH) {
                clearSecret(ctx, key)
                return null
            }
            val iv = data.copyOfRange(0, IV_LENGTH)
            val ct = data.copyOfRange(IV_LENGTH, data.size)
            val cipher = initCipher(ctx, Cipher.DECRYPT_MODE, iv) ?: return null
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            clearSecret(ctx, key)
            null
        }
    }

    fun clearSecret(ctx: Context, key: String) {
        securePrefs(ctx).edit().remove(key).apply()
    }

    fun clearAll(ctx: Context) {
        securePrefs(ctx).edit().clear().apply()
    }

    private fun securePrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun initCipher(ctx: Context, mode: Int, iv: ByteArray?): Cipher? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        if (!ks.containsAlias(KEY_ALIAS)) ensureKey(ctx)
        val key = (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: ensureKey(ctx) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (mode == Cipher.ENCRYPT_MODE) {
            cipher.init(mode, key)
        } else {
            cipher.init(mode, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        cipher
    } catch (_: Exception) {
        null
    }

    /** 生成(或取回)Android Keystore 中的 AES/GCM 密钥;256 位不可用时回退 128 位。 */
    private fun ensureKey(ctx: Context): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
        try {
            kg.init(builder.setKeySize(256).build())
        } catch (_: Exception) {
            kg.init(builder.setKeySize(128).build())
        }
        kg.generateKey()
    } catch (_: Exception) {
        null
    }
}
