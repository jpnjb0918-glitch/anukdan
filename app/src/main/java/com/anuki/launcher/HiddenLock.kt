package com.anuki.launcher

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast

/**
 * 숨긴 앱 잠금 (Prefs.lockHidden): the hidden apps list and hidden apps in the drawer search need the phone's own
 * fingerprint / face / PIN first. Unlocking lasts until the home screen goes to the background (or 5 minutes).
 */
object HiddenLock {
    const val REQ_CONFIRM = 31
    private const val KEEP_MS = 5 * 60 * 1000L
    private var unlockedAt = 0L
    private var pending: (() -> Unit)? = null

    /** Whether hidden apps may be shown right now. */
    fun isOpen(): Boolean {
        val p = LauncherApp.instance.prefs
        return !p.lockHidden || p.hidden.isEmpty() || System.currentTimeMillis() - unlockedAt < KEEP_MS
    }

    /** Lock again (the home screen went to the background). */
    fun relock() { unlockedAt = 0L }

    /** True when the phone has a screen lock (PIN, pattern, password); needed to turn the lock on. */
    fun deviceSecure(ctx: Context): Boolean = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Runs [onOpen] now if open, otherwise after the phone's fingerprint / PIN check. */
    fun require(a: Activity, onOpen: () -> Unit) {
        if (isOpen()) { onOpen(); return }
        if (!deviceSecure(a)) {
            // the screen lock was removed after the lock was turned on: nothing to check against
            unlockedAt = System.currentTimeMillis(); onOpen(); return
        }
        val title = "숨긴 앱 보기"
        val desc = "아늑이 런처의 숨긴 앱을 보려면 확인이 필요해요"
        if (Build.VERSION.SDK_INT >= 28) {
            val b = BiometricPrompt.Builder(a).setTitle(title).setDescription(desc)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            }
            try {
                b.build().authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        unlockedAt = System.currentTimeMillis()
                        LauncherApp.dlog("hidden apps unlocked")
                        onOpen()
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        LauncherApp.dlog("hidden apps unlock failed: $errorCode $errString")
                    }
                })
                return
            } catch (e: Exception) {
                LauncherApp.dlog("biometric prompt failed: $e")
            }
        }
        // Android 8: the system PIN / pattern screen
        val km = a.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION") val i: Intent? = km?.createConfirmDeviceCredentialIntent(title, desc)
        if (i == null) { Toast.makeText(a, "확인 화면을 열 수 없어요", Toast.LENGTH_SHORT).show(); return }
        pending = onOpen
        @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_CONFIRM)
    }

    /** From onActivityResult of the activity that called [require] on Android 8. */
    fun onResult(ok: Boolean) {
        val p = pending
        pending = null
        if (ok && p != null) { unlockedAt = System.currentTimeMillis(); p() }
    }
}
