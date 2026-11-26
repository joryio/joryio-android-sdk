package io.joryio.sdk.core

/**
 * What every request says about the SDK that sent it (D427), so the dashboard
 * can show which version an app runs and when a newer one is out.
 *
 * Same wire names on all five SDKs (the backend's sdk-version-parity spec
 * greps them): `X-Joryio-SDK` is the platform - the header the backend's
 * SDK-key guard and bot detection already read - and `X-Joryio-SDK-Version`
 * this build's version. A bridge (React Native, Unity) calls
 * [io.joryio.sdk.Joryio.setSdkWrapper] before initialize(), and its requests
 * then also carry `X-Joryio-SDK-Wrapper` and `X-Joryio-SDK-Wrapper-Version`:
 * the bridge's requests leave through this SDK, so it keeps saying `android`.
 */
internal object SdkIdentity {
    const val PLATFORM_HEADER = "X-Joryio-SDK"
    const val VERSION_HEADER = "X-Joryio-SDK-Version"
    const val WRAPPER_HEADER = "X-Joryio-SDK-Wrapper"
    const val WRAPPER_VERSION_HEADER = "X-Joryio-SDK-Wrapper-Version"
    const val PLATFORM = "android"

    private val WRAPPER_NAME = Regex("^[a-z][a-z0-9-]{0,31}$")
    private val VERSION = Regex("^[0-9A-Za-z.+-]{1,32}$")

    @Volatile
    private var wrapper: Pair<String, String>? = null

    /** False (and nothing changes) for a name or version that is not header-safe. */
    fun setWrapper(name: String?, version: String?): Boolean {
        val n = name?.trim()?.lowercase().orEmpty()
        val v = version?.trim().orEmpty()
        if (!WRAPPER_NAME.matches(n) || !VERSION.matches(v)) return false
        wrapper = n to v
        return true
    }

    /** The headers for one request, in a stable order. */
    fun headers(): List<Pair<String, String>> {
        val out = mutableListOf(PLATFORM_HEADER to PLATFORM, VERSION_HEADER to Logger.SDK_VERSION)
        wrapper?.let { (n, v) ->
            out += WRAPPER_HEADER to n
            out += WRAPPER_VERSION_HEADER to v
        }
        return out
    }

    /** Tests only. */
    internal fun resetForTest() {
        wrapper = null
    }
}
