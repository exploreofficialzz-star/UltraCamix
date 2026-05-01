package com.chastechgroup.camix.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import timber.log.Timber
import java.io.File

/**
 * Handles saving photos and videos to the device gallery via MediaStore.
 * This is the only correct approach for Android 10+ — file-based saving
 * goes to app-private storage that is invisible to the Photos/Gallery app.
 */
object MediaStoreSaver {

    // ── Photo ─────────────────────────────────────────────────────────────────

    fun buildPhotoOutputOptions(
        context: Context,
        displayName: String = "CamixUltra_${System.currentTimeMillis()}"
    ): ImageCapture.OutputFileOptions {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — save directly to MediaStore (shows in gallery)
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/CamixUltra")
            }
            ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
        } else {
            // Android 9 and below — use file-based save to Pictures
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "CamixUltra"
            ).apply { if (!exists()) mkdirs() }
            val file = File(dir, "$displayName.jpg")
            ImageCapture.OutputFileOptions.Builder(file).build()
        }
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    fun buildVideoOutputOptions(
        context: Context,
        displayName: String = "CamixUltra_${System.currentTimeMillis()}"
    ): MediaStoreOutputOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/CamixUltra")
            }
        }
        return MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()
    }

    // ── Timelapse (saves individual frames to gallery) ────────────────────────

    fun buildTimelapseFrameOptions(
        context: Context,
        frameNumber: Int
    ): ImageCapture.OutputFileOptions {
        val displayName = "CamixUltra_TL_${System.currentTimeMillis()}_f$frameNumber"
        return buildPhotoOutputOptions(context, displayName)
    }

    // ── Resolve saved URI to a display path string ────────────────────────────

    fun resolveDisplayPath(context: Context, uri: Uri?): String {
        if (uri == null) return "Unknown location"
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(0) ?: uri.lastPathSegment ?: "Saved"
                else uri.lastPathSegment ?: "Saved"
            } ?: uri.lastPathSegment ?: "Saved"
        } catch (e: Exception) {
            Timber.e(e, "MediaStoreSaver: path resolve failed")
            "Saved"
        }
    }
}
