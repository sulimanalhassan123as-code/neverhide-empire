package com.neverhide.empire.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * APK Verifier — anti-tamper security for the OTA update channel.
 *
 * Before ANY downloaded update is offered for install, we compare the
 * signing certificate of the downloaded APK against the certificate of
 * the already-installed app. If they do not match byte-for-byte, the
 * download is a forgery (or was corrupted/swapped in transit) and is
 * destroyed.
 *
 * Uses GET_SIGNING_CERTIFICATES (API 28+) — the modern, reliable API for
 * apps signed with APK Signature Scheme v2/v3 (which is all our builds).
 * The old deprecated GET_SIGNATURES flag is kept only as a last-resort
 * fallback for pre-API-28 devices.
 */
object ApkVerifier {

    private fun firstCert(context: Context, pkgName: String?, archivePath: String?): ByteArray? {
        val pm = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val info = if (archivePath != null) {
                    pm.getPackageArchiveInfo(archivePath, flags)
                } else {
                    pm.getPackageInfo(pkgName!!, flags)
                }
                val signingInfo = info?.signingInfo
                val certs = signingInfo?.apkContentsSigners ?: signingInfo?.signingCertificateHistory
                certs?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val flags = PackageManager.GET_SIGNATURES
                val info = if (archivePath != null) {
                    pm.getPackageArchiveInfo(archivePath, flags)
                } else {
                    pm.getPackageInfo(pkgName!!, flags)
                }
                @Suppress("DEPRECATION")
                info?.signatures?.firstOrNull()?.toByteArray()
            }
        }.getOrNull()
    }

    /** @return true when the downloaded APK is signed with the SAME
     *  certificate as the currently installed Neverhide Empire. */
    fun signatureMatchesInstalled(context: Context, downloadedApk: File): Boolean {
        val installed = firstCert(context, context.packageName, null) ?: return false
        val downloaded = firstCert(context, null, downloadedApk.absolutePath) ?: return false
        return MessageDigest.isEqual(installed, downloaded)
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
