package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

internal suspend fun PersonalReleaseUpdate.saveVerifiedBytes(input: InputStream, target: File) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    target.outputStream().buffered().use { output ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= apkSize)
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
        }
    }
    check(total == apkSize && digest.digest().toHexString() == apkSha256)
}

private fun File.hasDigest(expected: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(inputStream(), digest).use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (stream.read(buffer) >= 0) { /* consume file */ }
    }
    return digest.digest().toHexString() == expected
}

@Suppress("DEPRECATION")
private fun verifyArchive(context: Context, apk: File, release: PersonalReleaseUpdate) {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = checkNotNull(pm.getPackageArchiveInfo(apk.path, flags))
    release.checkArchive(archive, pm.getPackageInfo(context.packageName, 0))
}

@Suppress("DEPRECATION")
internal fun PersonalReleaseUpdate.checkArchive(archive: PackageInfo, installed: PackageInfo) {
    val signers = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
    val signer = checkNotNull(signers?.singleOrNull())
    val certificate = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).toHexString()
    check(archive.packageName == PersonalReleaseFeed.APPLICATION_ID && archive.packageName == installed.packageName)
    check(certificate == PersonalReleaseFeed.CERTIFICATE_SHA256)
    check(PackageInfoCompat.getLongVersionCode(archive) == versionCode.toLong())
    check(versionCode > PackageInfoCompat.getLongVersionCode(installed) && archive.versionName == versionName)
    check(checkNotNull(archive.applicationInfo).flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
}

/** Only expose the private, verified bytes to Android's installer. A dismissed installer can be retried offline. */
internal suspend fun preparePersonalInstall(
    context: Context,
    release: PersonalReleaseUpdate,
    download: suspend (File) -> Unit,
): Intent {
    val directory = File(context.cacheDir, "personal-updates")
    check(directory.isDirectory || directory.mkdirs())
    val verified = File(directory, "${release.apkSha256}.apk")
    directory.listFiles()?.filter { it != verified }?.forEach { it.delete() }
    if (verified.length() != release.apkSize || !verified.hasDigest(release.apkSha256)) {
        val temporary = File.createTempFile("incoming-", ".apk", directory)
        try {
            download(temporary)
            verifyArchive(context, temporary, release)
            check(temporary.renameTo(verified))
        } finally {
            temporary.delete()
        }
    } else {
        verifyArchive(context, verified, release)
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", verified)
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
