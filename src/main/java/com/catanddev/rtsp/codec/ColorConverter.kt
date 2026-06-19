package com.catanddev.rtsp.codec

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import java.nio.ByteBuffer

/**
 * Convert Image YUV 4:2:0 888 to Bitmap ARGB 8888.
 * Modified version without androidx.camera.core dependencies
 */
class ColorConverterImageAndroidX : ColorConverterImage() {

    override fun getBitmapFromImage(image: Image): Bitmap {
        return convertYUV420ToBitmap(image)
    }

    override fun release() {
        // No resources to release
    }

    /**
     * Convert YUV 420 888 Image to Bitmap ARGB 8888
     */
    private fun convertYUV420ToBitmap(image: Image): Bitmap {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888 image format"
        }

        val width = image.width
        val height = image.height
        val planes = image.planes

        // Y, U, and V planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val yRowStride = planes[0].rowStride
        val uRowStride = planes[1].rowStride
        val vRowStride = planes[2].rowStride
        val yPixelStride = planes[0].pixelStride
        val uPixelStride = planes[1].pixelStride
        val vPixelStride = planes[2].pixelStride

        val argbArray = IntArray(width * height)

        // Convert YUV to ARGB
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Y component
                val yIndex = (y * yRowStride) + (x * yPixelStride)
                val yValue = (yBuffer[yIndex].toInt() and 0xFF)

                // UV coordinates (subsampled by 2)
                val uvX = x / 2
                val uvY = y / 2

                // U component
                val uIndex = (uvY * uRowStride) + (uvX * uPixelStride)
                val uValue = (uBuffer[uIndex].toInt() and 0xFF) - 128

                // V component
                val vIndex = (uvY * vRowStride) + (uvX * vPixelStride)
                val vValue = (vBuffer[vIndex].toInt() and 0xFF) - 128

                // YUV to RGB conversion
                var r = yValue + (1.370705 * vValue).toInt()
                var g = yValue - (0.337633 * uValue).toInt() - (0.698001 * vValue).toInt()
                var b = yValue + (1.732446 * uValue).toInt()

                // Clamp values to [0, 255]
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                // ARGB format
                argbArray[y * width + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(argbArray, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Alternative method using YuvImage (for JPEG encoding path)
     */
    private fun convertYUV420ToBitmapUsingYuvImage(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val planes = image.planes

        // Get YUV data
        val yuvData = getNV21Data(image, width, height, planes)

        // This would require using YuvImage and encoding to JPEG then decoding back
        // which is inefficient. The direct conversion above is better.

        // For now, fall back to direct conversion
        return convertYUV420ToBitmap(image)
    }

    /**
     * Extract NV21 format data from YUV_420_888 image
     */
    private fun getNV21Data(
        image: Image,
        width: Int,
        height: Int,
        planes: Array<Image.Plane>
    ): ByteArray {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // For NV21, we need interleaved VU data
        // This is a simplified version - actual NV21 conversion is more complex
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        // This is a placeholder - full NV21 conversion would require
        // proper handling of strides and interleaving
        return nv21
    }
}