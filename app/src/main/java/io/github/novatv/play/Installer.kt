package io.github.novatv.play

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Hands a downloaded release to Android's installer, which updates the app in place and keeps its data. */
object Installer {
    /** The release file for this build: the TV's "NovaPlay-1.2.apk" or the phone's "NovaPlay-Android-1.2.apk". */
    val asset = if (PHONE) Regex("""NovaPlay-Android-[\d.]+\.apk""") else Regex("""NovaPlay-[\d.]+\.apk""")

    fun install(ctx: Context, file: File) {
        // Android 8 and later ask once whether NovaPlay may install apps; the switch is in this screen.
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Allow NovaPlay to install apps, then choose Update again", Toast.LENGTH_LONG).show()
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) { } // Some TVs have no such screen; the installer asks instead.
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
