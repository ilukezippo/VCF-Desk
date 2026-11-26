package com.ilukezippo.vcfdesk.data.google

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sign-in failed or was cancelled; [message] is readable. */
class GoogleAuthException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Google account choice and People API access tokens through Play Services' AuthorizationClient
 * (scope `contacts`). No client ID is in code: the OAuth client is matched by package + signing SHA-1.
 * Holds no UI of its own; it launches the system account chooser / consent screen on [activity]
 * when needed. Results are delivered through the activity's result registry, so these calls work
 * at any point of the activity's life (not only before it starts).
 */
class GoogleAuth(private val activity: ComponentActivity) {
    private val client = Identity.getAuthorizationClient(activity)
    private val consent = Mutex()

    /** The system "choose a Google account" screen. */
    fun pickAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(null, null, arrayOf(ACCOUNT_TYPE), null, null, null, null)

    /** Shows the account chooser; returns the chosen email, or null if the user backed out. */
    suspend fun pickAccount(): String? {
        val r = launch(ActivityResultContracts.StartActivityForResult(), pickAccountIntent())
        if (r.resultCode != Activity.RESULT_OK) return null
        return r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.takeIf { it.isNotBlank() }
    }

    /**
     * An access token for [email]'s contacts. When consent is needed the consent screen is shown
     * (one at a time); throws [GoogleAuthException] if the user declines or authorization fails.
     */
    suspend fun token(email: String): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(CONTACTS_SCOPE)))
            .setAccount(Account(email, ACCOUNT_TYPE))
            .build()
        val first = try {
            client.authorize(request).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw GoogleAuthException("Google authorization failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val result = if (first.hasResolution()) consent.withLock { resolve(first) } else first
        return result.accessToken?.takeIf { it.isNotEmpty() }
            ?: throw GoogleAuthException("Google did not grant access to contacts")
    }

    /** Drops a cached token (e.g. after the People API answered 401) so the next [token] gets a fresh one. */
    suspend fun clearToken(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    private suspend fun resolve(pending: AuthorizationResult): AuthorizationResult {
        val pi = pending.pendingIntent ?: throw GoogleAuthException("Google needs consent but gave no screen to ask for it")
        val r = launch(ActivityResultContracts.StartIntentSenderForResult(), IntentSenderRequest.Builder(pi.intentSender).build())
        if (r.resultCode != Activity.RESULT_OK) throw GoogleAuthException(
            "Google sign-in was cancelled or refused. If you didn't cancel it, Google sign-in isn't set up " +
                "for this app yet: see docs/android/GOOGLE_SETUP.md (package com.ilukezippo.vcfdesk + this build's SHA-1)."
        )
        return try {
            client.getAuthorizationResultFromIntent(r.data)
        } catch (e: Exception) {
            throw GoogleAuthException("Google authorization failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Launches [input] through the activity's result registry and suspends until the result. */
    private suspend fun <I> launch(contract: ActivityResultContract<I, ActivityResult>, input: I): ActivityResult =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                lateinit var launcher: ActivityResultLauncher<I>
                launcher = activity.activityResultRegistry.register("vcfdesk-google-${UUID.randomUUID()}", contract) { res ->
                    launcher.unregister()
                    if (cont.isActive) cont.resume(res)
                }
                cont.invokeOnCancellation { launcher.unregister() }
                launcher.launch(input)
            }
        }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    companion object {
        const val ACCOUNT_TYPE = "com.google"
        const val CONTACTS_SCOPE = "https://www.googleapis.com/auth/contacts"
    }
}
