package com.example.gallery.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A FrameLayout that measures itself as a square (height = width).
 * Used in gallery grids to ensure uniform square thumbnails.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force height to match width for a square layout
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val squareHeightSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, squareHeightSpec)
    }
}
