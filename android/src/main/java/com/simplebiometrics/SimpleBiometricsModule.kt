package com.simplebiometrics

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.UiThreadUtil.runOnUiThread
import com.facebook.react.module.annotations.ReactModule


@ReactModule(name = SimpleBiometricsModule.NAME)
class SimpleBiometricsModule(reactContext: ReactApplicationContext) :
  NativeSimpleBiometricsSpec(reactContext), LifecycleEventListener {

  data class PendingAuthRequest(
    val promptTitle: String?,
    val promptMessage: String?,
    val allowDeviceCredentials: Boolean,
    val promise: Promise
  )

  private var pendingAuthRequest: PendingAuthRequest? = null
  private var launchAttemptScheduled = false

  init {
    reactContext.addLifecycleEventListener(this)
  }

  override fun getName(): String {
    return NAME
  }

  companion object {
    const val NAME = "SimpleBiometrics"
  }

  override fun onHostResume() {
    launchPendingAuthentication()
  }

  override fun onHostPause() {
    // No-op; pending auth remains queued until host resumes.
  }

  override fun onHostDestroy() {
    // No-op; pending auth remains queued until activity is available again.
  }

  override fun invalidate() {
    reactApplicationContext.removeLifecycleEventListener(this)
    pendingAuthRequest = null
    launchAttemptScheduled = false
    super.invalidate()
  }

  /**
   * Helper to choose allowed authenticators depending on API level and JS param.
   */
  private fun getAllowedAuthenticators(allowDeviceCredentials: Boolean): Int {
    if (allowDeviceCredentials) {
      return BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
    // Default to biometrics only
    return BiometricManager.Authenticators.BIOMETRIC_STRONG or
      BiometricManager.Authenticators.BIOMETRIC_WEAK
  }

  override fun canAuthenticate(
    allowDeviceCredentials: Boolean,
    promise: Promise?
  ) {
    try {
      val context = reactApplicationContext
      val biometricManager = BiometricManager.from(context)

      val authenticators = getAllowedAuthenticators(allowDeviceCredentials)
      val res = biometricManager.canAuthenticate(authenticators)
      val can = res == BiometricManager.BIOMETRIC_SUCCESS

      promise!!.resolve(can)
    } catch (e: Exception) {
      promise!!.reject(e)
    }
  }

  override fun requestBioAuth(
    promptTitle: String?,
    promptMessage: String?,
    allowDeviceCredentials: Boolean,
    promise: Promise?
  ) {
    runOnUiThread {
      if (promise == null) {
        return@runOnUiThread
      }

      if (pendingAuthRequest != null) {
        promise.reject("BIOMETRIC_IN_PROGRESS", "Another biometric authentication request is already in progress")
        return@runOnUiThread
      }

      pendingAuthRequest = PendingAuthRequest(
        promptTitle = promptTitle,
        promptMessage = promptMessage,
        allowDeviceCredentials = allowDeviceCredentials,
        promise = promise
      )

      launchPendingAuthentication()
    }
  }

  private fun launchPendingAuthentication() {
    runOnUiThread {
      val request = pendingAuthRequest ?: return@runOnUiThread
      val activity = reactApplicationContext.currentActivity as? FragmentActivity ?: return@runOnUiThread

      if (activity.isFinishing || activity.isDestroyed) {
        return@runOnUiThread
      }

      if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        if (!launchAttemptScheduled) {
          launchAttemptScheduled = true
          activity.window?.decorView?.post {
            launchAttemptScheduled = false
            launchPendingAuthentication()
          } ?: run {
            launchAttemptScheduled = false
          }
        }
        return@runOnUiThread
      }

      try {
        val context = reactApplicationContext
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val authenticationCallback: BiometricPrompt.AuthenticationCallback =
          object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
              super.onAuthenticationError(errorCode, errString)
              pendingAuthRequest = null
              if (errorCode == BiometricPrompt.ERROR_CANCELED) {
                request.promise.reject("BIOMETRIC_SYSTEM_CANCELED", errString.toString())
              } else {
                request.promise.reject(java.lang.Exception(errString.toString()))
              }
            }

            override fun onAuthenticationSucceeded(
              result: BiometricPrompt.AuthenticationResult
            ) {
              super.onAuthenticationSucceeded(result)
              pendingAuthRequest = null
              request.promise.resolve(true)
            }
          }

        val prompt = BiometricPrompt(
          activity,
          mainExecutor,
          authenticationCallback
        )

        val authenticators = getAllowedAuthenticators(request.allowDeviceCredentials)
        val promptInfo: BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
          .setAllowedAuthenticators(authenticators)
          .setTitle(request.promptTitle ?: "")
          .setSubtitle(request.promptMessage)
          .build()

        prompt.authenticate(promptInfo)
      } catch (e: java.lang.Exception) {
        pendingAuthRequest = null
        request.promise.reject(e)
      }
    }
  }
}
