package com.neverhide.empire.updater

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * APK Verifier — anti-tamper security for the OTA update channel.
 *
 * Before ANY downloaded update is offered for install, we compare the
 * signing certificate of the downloaded APK against the certificate of
 * the already-installed app. If they do not match byte-for-byte, the
 * download is a forgery (or was swapped in transit) and is destroyed.
 *
 * This means even if someone hijacks the update URL or tricks the
 * updater into fetching their APK, Android's signature rule + this
 * check make the poison update impossible to install.
 */
object ApkVerifier {

    /** @return true when the downloaded APK is signed with the SAME
     *  certificate as the currently installed Neverhide Empire. */
    fun signatureMatchesInstalled(context: Context, downloadedApk: File): Boolean {
        return runCatching {
            val pm = context.packageManager

            // Signature of the app we're currently running
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull() ?: return false

            // Signature of the file we just downloaded
            val downloaded = pm.getPackageArchiveInfo(
                downloadedApk.absolutePath, PackageManager.GET_SIGNATURES
            )?.signatures?.firstOrNull() ?: return false

            MessageDigest.isEqual(installed.toByteArray(), downloaded.toByteArray())
        }.getOrDefault(false)
    }

    /** Also verify the package name matches — a same-signed but different
     *  package could otherwise be smuggled in. */
    fun packageMatchesInstalled(context: Context, downloadedApk: File): Boolean {
        return runCatching {
            val pkg = context.packageManager.getPackageArchiveInfo(downloadedApk.absolutePath, 0)
            pkg?.packageName == context.packageName
        }.getOrDefault(false)
    }
}
