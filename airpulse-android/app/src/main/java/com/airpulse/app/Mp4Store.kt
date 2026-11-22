package com.airpulse.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MP4 files in Movies/AirPulse (MediaStore on Android 10+, app folder before that). */
object Mp4Store {
    class Target(val name: String, val uri: Uri?, val pfd: ParcelFileDescriptor)

    fun open(context: Context): Target {
        val name = "AirPulse_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AirPulse")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = try {
                resolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("open failed")
            } catch (ex: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw ex
            }
            return Target(name, uri, pfd)
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AirPulse").apply { mkdirs() }
        val pfd = ParcelFileDescriptor.open(
            File(dir, name),
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        return Target(name, null, pfd)
    }

    /** Publishes the file (ok) or deletes it. Returns the text to show. */
    fun finish(context: Context, target: Target, ok: Boolean): String {
        runCatching { target.pfd.close() }
        val uri = target.uri
        if (!ok) {
            if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
            return context.t(R.string.record_failed)
        }
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        return context.t(R.string.saved, "Movies/AirPulse/" + target.name)
    }
}
