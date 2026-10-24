package io.joryio.sdk.push

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Invisible Activity that asks for POST_NOTIFICATIONS on behalf of a host
 * Activity that cannot (a plain `android.app.Activity`, which has no
 * ActivityResultRegistry - Unity's player Activity is one).
 *
 * It exists so the answer is reported when the user ANSWERS. The fallback it
 * replaces reported synchronously while the system dialog was still showing,
 * which always read as "denied".
 *
 * Translucent and excluded from recents, like the push trampoline; it finishes
 * as soon as the dialog closes. Declared in the SDK's manifest, so it merges
 * into the app with nothing to add.
 */
class JoryioPermissionActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        deliver(granted)
    }

    private var requestId: Int = -1
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent?.getIntExtra(EXTRA_REQUEST_ID, -1) ?: -1
        // Recreated after a configuration change: the registry redelivers the
        // pending result, so do not ask twice.
        if (savedInstanceState == null) {
            try {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (t: Throwable) {
                deliver(false)
            }
        }
    }

    private fun deliver(granted: Boolean) {
        if (delivered) return
        delivered = true
        pending.remove(requestId)?.let { cb ->
            try {
                cb(granted)
            } catch (t: Throwable) {
                android.util.Log.w("Joryio", "Push permission callback threw: ${t.message}")
            }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        // Destroyed without an answer (killed, back-pressed before the dialog):
        // still report, so a caller waiting on a callback is never left hanging.
        if (isFinishing && !delivered) deliver(false)
        super.onDestroy()
    }

    internal companion object {
        private const val EXTRA_REQUEST_ID = "io.joryio.sdk.permission_request_id"
        private val nextId = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, (Boolean) -> Unit>()

        fun launch(from: Activity, callback: (Boolean) -> Unit) {
            val id = nextId.getAndIncrement()
            pending[id] = callback
            try {
                from.startActivity(
                    Intent(from, JoryioPermissionActivity::class.java)
                        .putExtra(EXTRA_REQUEST_ID, id),
                )
                @Suppress("DEPRECATION")
                from.overridePendingTransition(0, 0)
            } catch (t: Throwable) {
                pending.remove(id)
                callback(false)
            }
        }
    }
}
