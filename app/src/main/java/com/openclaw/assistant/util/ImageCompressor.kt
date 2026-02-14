package com.openclaw.assistant.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max

private const val TAG = "ImageCompressor"
const val MAX_FILE_SIZE_BYTES = 4 * 1024 * 1024 // 4MB
private const val MAX_IMAGE_DIMENSION = 2048
private const val JPEG_QUALITY = 80
private const val JPEG_QUALITY_FALLBACK = 60

object ImageCompressor {

    fun processAttachment(context: Context, uri: Uri): AttachmentData? {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val fileName = getFileName(context, uri) ?: "file"

        return if (mimeType.startsWith("image/")) {
            compressImage(context, uri, mimeType, fileName)
        } else {
            readFileAsBase64(context, uri, mimeType, fileName)
        }
    }

    private fun compressImage(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String
    ): AttachmentData? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

            // Decode bounds first
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // Calculate sample size for downscaling
            val maxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > MAX_IMAGE_DIMENSION * 2) {
                sampleSize *= 2
            }

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val stream2 = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(stream2, null, decodeOptions)
            stream2.close()

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from $uri")
                return null
            }

            // Scale down if still too large
            val scaledBitmap = scaleBitmap(bitmap, MAX_IMAGE_DIMENSION)

            // Compress to JPEG
            var base64 = compressBitmapToBase64(scaledBitmap, JPEG_QUALITY)

            // If too large, retry with lower quality
            if (Base64.decode(base64, Base64.NO_WRAP).size > MAX_FILE_SIZE_BYTES) {
                base64 = compressBitmapToBase64(scaledBitmap, JPEG_QUALITY_FALLBACK)
            }

            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            bitmap.recycle()

            if (Base64.decode(base64, Base64.NO_WRAP).size > MAX_FILE_SIZE_BYTES) {
                Log.w(TAG, "Image still too large after compression")
                return null
            }

            AttachmentData(base64 = base64, mimeType = "image/jpeg", fileName = fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image", e)
            null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = maxDimension.toFloat() / max(width, height).toFloat()
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun compressBitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun readFileAsBase64(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileName: String
    ): AttachmentData? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()

            if (bytes.size > MAX_FILE_SIZE_BYTES) {
                Log.w(TAG, "File too large: ${bytes.size} bytes")
                return null
            }

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            AttachmentData(base64 = base64, mimeType = mimeType, fileName = fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file", e)
            null
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment
    }
}
