package com.example.scanner

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Prepares high-quality JPG files via [FileProvider] for direct sharing of scanned pages:
 * 1. Global Cross-App Drag & Drop (`DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`) carrying an
 *    `image/jpeg` URI — natively triggers Dynamic Island / Super Island / Origin Island / Magic
 *    Portal / File Dock drop targets on supported modern Android devices.
 * 2. Instant fallback to Android Sharesheet (`Intent.ACTION_SEND`, `image/jpeg`) when released
 *    without dropping onto an island or on devices without drag-to-island support.
 */
object ScanShareHelper {

    private const val SHARED_SCANS_SUBDIR = "shared_scans"

    private fun sharedScansDir(context: Context): File =
        File(context.cacheDir, SHARED_SCANS_SUBDIR).apply { mkdirs() }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "Scan" }
            .take(40)

    /**
     * Writes (or reuses an up-to-date) high-quality JPG file for a scanned page in cache storage.
     */
    fun prepareJpgFileSync(
        context: Context,
        pageId: Long,
        sessionTitle: String,
        pageNumber: Int,
        bitmap: Bitmap,
        generationTag: Int = bitmap.generationId,
    ): File {
        val dir = sharedScansDir(context)
        val safeTitle = sanitizeFileName(sessionTitle)
        val target = File(dir, "${safeTitle}_p${pageNumber}_${pageId}_${generationTag}.jpg")
        if (target.exists() && target.length() > 0L) {
            return target
        }
        // Remove stale versions of the same pageId after rotate/crop
        dir.listFiles()?.forEach { existing ->
            if (existing.name.contains("_${pageId}_") && existing != target) {
                existing.delete()
            }
        }
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
        }
        return target
    }

    suspend fun prepareJpgFile(
        context: Context,
        pageId: Long,
        sessionTitle: String,
        pageNumber: Int,
        bitmap: Bitmap,
    ): File = withContext(Dispatchers.IO) {
        prepareJpgFileSync(
            context = context,
            pageId = pageId,
            sessionTitle = sessionTitle,
            pageNumber = pageNumber,
            bitmap = bitmap,
        )
    }

    fun getJpgContentUri(context: Context, jpgFile: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            jpgFile,
        )

    /**
     * Builds a global cross-app [DragAndDropTransferData] payload carrying the scanned page as a
     * standard `image/jpeg` content URI so Dynamic Island / Super Island / File Dock / split-screen
     * apps can immediately accept the dropped JPG.
     */
    fun createJpgDragTransferData(
        context: Context,
        jpgFile: File,
    ): DragAndDropTransferData {
        val uri = getJpgContentUri(context, jpgFile)
        val clipData = ClipData(
            ClipDescription(jpgFile.name, arrayOf("image/jpeg")),
            ClipData.Item(uri),
        )
        val dragFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
        } else {
            0
        }
        return DragAndDropTransferData(
            clipData = clipData,
            localState = jpgFile,
            flags = dragFlags,
        )
    }

    /**
     * Opens Android's native Share Sheet directly with a single scanned page as a `.jpg` image.
     */
    fun shareSingleJpg(
        context: Context,
        jpgFile: File,
        chooserTitle: String,
    ) {
        runCatching {
            val uri = getJpgContentUri(context, jpgFile)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, jpgFile.name)
                clipData = ClipData.newUri(context.contentResolver, jpgFile.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    /**
     * Opens Android's native Share Sheet with multiple selected scanned pages as `.jpg` images.
     */
    fun shareMultipleJpgs(
        context: Context,
        jpgFiles: List<File>,
        chooserTitle: String,
    ) {
        if (jpgFiles.isEmpty()) return
        if (jpgFiles.size == 1) {
            shareSingleJpg(context, jpgFiles.first(), chooserTitle)
            return
        }
        runCatching {
            val uris = ArrayList<Uri>(jpgFiles.size)
            var clip: ClipData? = null
            jpgFiles.forEach { file ->
                val uri = getJpgContentUri(context, file)
                uris.add(uri)
                if (clip == null) {
                    clip = ClipData.newUri(context.contentResolver, file.name, uri)
                } else {
                    clip?.addItem(ClipData.Item(uri))
                }
            }
            val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }
}
