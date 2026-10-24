package io.joryio.sdk.core

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.TimeZone

internal object DeviceInfo {
    fun getTrackingProperties(context: Context, deviceId: String): Map<String, Any> {
        val properties = mutableMapOf<String, Any>()

        properties["\$device_id"] = deviceId
        properties["\$platform"] = "android"
        properties["\$manufacturer"] = Build.MANUFACTURER
        properties["\$model"] = Build.MODEL
        properties["\$os_name"] = "Android"
        properties["\$os_version"] = Build.VERSION.RELEASE ?: "unknown"
        properties["\$os_sdk_int"] = Build.VERSION.SDK_INT

        val packageName = context.packageName
        properties["\$package_name"] = packageName

        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        properties["\$app_version"] = packageInfo.versionName ?: "unknown"
        val buildNumber = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        properties["\$build_number"] = buildNumber

        val metrics = context.resources.displayMetrics
        properties["\$screen_width"] = metrics.widthPixels
        properties["\$screen_height"] = metrics.heightPixels

        var localeSet = false
        // configuration.locales needs API 24; minSdk is 23, so the pre-24 path below
        // (Locale.getDefault) is still reachable.
        if (Build.VERSION.SDK_INT >= 24) {
            val locales = context.resources.configuration.locales
            if (locales.size() > 0) {
                val primaryLocale = locales.get(0)
                properties["\$locale"] = primaryLocale.toLanguageTag()
                properties["\$language"] = primaryLocale.language
                val languageTags = (0 until locales.size()).map { index ->
                    locales.get(index).toLanguageTag()
                }
                properties["\$languages"] = languageTags
                localeSet = true
            }
        }
        if (!localeSet) {
            val locale = Locale.getDefault()
            properties["\$locale"] = locale.toLanguageTag()
            properties["\$language"] = locale.language
            properties["\$languages"] = listOf(locale.toLanguageTag())
        }

        properties["\$timezone"] = TimeZone.getDefault().id

        return properties
    }

    /**
     * What the device IS, for push registration - UNPREFIXED, matching iOS.
     *
     * Android sent no device info with a push token at all, so an Android
     * device row carried no model, os_version or app_version. Segment filters
     * over those fields matched zero Android users, and did it silently: a
     * device with no app_version simply loses a version comparison rather than
     * erroring. A "3.2 and above" feature announcement would have gone to iOS
     * only, and looked like it worked.
     *
     * Keys deliberately mirror iOS's DeviceInfo.getDeviceInfo() - `app_version`,
     * not `$app_version` - because one segment filter reads both platforms.
     * Different names here would mean a filter that quietly works on one and not
     * the other, which is the harder bug to see. The tracking properties above
     * keep their `$` prefix; that is the event vocabulary, a separate contract.
     */
    fun getDeviceInfo(context: Context): Map<String, Any> {
        val info = mutableMapOf<String, Any>()

        info["model"] = Build.MODEL
        info["manufacturer"] = Build.MANUFACTURER
        info["os_name"] = "Android"
        info["os_version"] = Build.VERSION.RELEASE ?: "unknown"
        info["os_sdk_int"] = Build.VERSION.SDK_INT

        val packageName = context.packageName
        info["package_name"] = packageName
        try {
            val packageManager = context.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info["app_version"] = packageInfo.versionName ?: "unknown"
            info["build_number"] = if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            // A device whose own package cannot be read must still register its
            // push token - losing the token would cost far more than losing the
            // version string.
        }

        val metrics = context.resources.displayMetrics
        info["screen_width"] = metrics.widthPixels
        info["screen_height"] = metrics.heightPixels

        info["locale"] = Locale.getDefault().toLanguageTag()
        info["timezone"] = TimeZone.getDefault().id

        return info
    }
}
