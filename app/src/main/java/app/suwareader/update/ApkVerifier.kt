package app.suwareader.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

sealed interface ApkVerdict {
    data object Ok : ApkVerdict
    data class DigestMismatch(val expected: String, val actual: String) : ApkVerdict
    data object SignatureMismatch : ApkVerdict
    data class WrongPackage(val expected: String, val actual: String) : ApkVerdict
    data class Unreadable(val reason: String) : ApkVerdict
}

/**
 * Proves a downloaded APK is ours before it goes anywhere near the installer (ported from Phos): its sha256 must match
 * the release manifest, and it must be the same package signed by a key the running app is signed with.
 */
class ApkVerifier(private val context: Context) {

    fun verify(file: File, expectedSha256: String): ApkVerdict {
        val actual = try {
            sha256(file)
        } catch (e: Exception) {
            return ApkVerdict.Unreadable("couldn't read the download (${e.message})")
        }
        if (!actual.equals(expectedSha256, ignoreCase = true)) return ApkVerdict.DigestMismatch(expectedSha256.lowercase(), actual)

        val pm = context.packageManager
        val running = try {
            identity(pm.getPackageInfo(context.packageName, FLAGS))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } ?: return ApkVerdict.Unreadable("couldn't read this app's own signing certificate")
        val downloaded = pm.getPackageArchiveInfo(file.absolutePath, FLAGS)?.let(::identity)
            ?: return ApkVerdict.Unreadable("the download isn't a readable APK")
        if (downloaded.first != running.first) return ApkVerdict.WrongPackage(running.first, downloaded.first)
        if (downloaded.second.intersect(running.second).isEmpty()) return ApkVerdict.SignatureMismatch
        return ApkVerdict.Ok
    }

    private fun identity(info: PackageInfo): Pair<String, Set<String>>? {
        val sigs: List<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val s = info.signingInfo ?: return null
            if (s.hasMultipleSigners()) s.apkContentsSigners?.toList().orEmpty() else s.signingCertificateHistory?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        if (sigs.isEmpty()) return null
        return info.packageName to sigs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private companion object {
        @Suppress("DEPRECATION")
        val FLAGS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    }
}

fun ApkVerdict.refusalMessage(): String? = when (this) {
    ApkVerdict.Ok -> null
    is ApkVerdict.DigestMismatch -> "Refused to install: the download doesn't match the checksum in the release."
    ApkVerdict.SignatureMismatch -> "Refused to install: that APK is signed by a different key than this copy of Suwa."
    is ApkVerdict.WrongPackage -> "Refused to install: that APK is $actual, not $expected."
    is ApkVerdict.Unreadable -> "Refused to install: $reason."
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
