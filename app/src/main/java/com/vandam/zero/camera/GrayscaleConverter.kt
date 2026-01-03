package com.vandam.zero.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Converts images to grayscale using the ITU-R BT.709 standard (HDTV/sRGB).
 * 
 * This uses luminance-preserving coefficients that match human visual perception:
 * - Red: 21.26%
 * - Green: 71.52%
 * - Blue: 7.22%
 * 
 * These weights ensure the grayscale image retains the same apparent brightness
 * (luminance) as the original color image.
 */
object GrayscaleConverter {
    
    // BT.709 luminance coefficients (public for reuse)
    const val R_WEIGHT = 0.2126f
    const val G_WEIGHT = 0.7152f
    const val B_WEIGHT = 0.0722f
    
    /**
     * ColorMatrix that converts RGB to grayscale using BT.709 coefficients.
     * Each row (R, G, B) gets the same weighted sum, preserving luminance.
     */
    private val GRAYSCALE_MATRIX = floatArrayOf(
        R_WEIGHT, G_WEIGHT, B_WEIGHT, 0f, 0f,  // Red output
        R_WEIGHT, G_WEIGHT, B_WEIGHT, 0f, 0f,  // Green output
        R_WEIGHT, G_WEIGHT, B_WEIGHT, 0f, 0f,  // Blue output
        0f,       0f,       0f,       1f, 0f   // Alpha (unchanged)
    )
    
    private val grayscaleColorMatrix = ColorMatrix(GRAYSCALE_MATRIX)
    private val grayscaleFilter = ColorMatrixColorFilter(grayscaleColorMatrix)
    
    /**
     * Returns the ColorMatrixColorFilter for applying grayscale effect to Views.
     * Uses the same BT.709 coefficients as image conversion.
     */
    fun getColorFilter(): ColorMatrixColorFilter = grayscaleFilter
    
    /**
     * Converts a bitmap to grayscale using BT.709 luminance coefficients.
     * 
     * @param source The source bitmap to convert
     * @return A new grayscale bitmap (caller is responsible for recycling)
     */
    fun toGrayscale(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(
            source.width,
            source.height,
            source.config ?: Bitmap.Config.ARGB_8888
        )
        
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = grayscaleFilter
            isAntiAlias = false
            isFilterBitmap = false
        }
        
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }
    
    /**
     * Converts a bitmap to grayscale in-place by creating a new bitmap
     * and returning it. The source bitmap is NOT recycled.
     * 
     * @param source The source bitmap
     * @param recycleSource If true, recycles the source bitmap after conversion
     * @return A new grayscale bitmap
     */
    fun toGrayscale(source: Bitmap, recycleSource: Boolean): Bitmap {
        val result = toGrayscale(source)
        if (recycleSource && result != source) {
            source.recycle()
        }
        return result
    }
}
