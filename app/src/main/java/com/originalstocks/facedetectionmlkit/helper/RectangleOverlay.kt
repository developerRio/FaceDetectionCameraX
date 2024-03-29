package com.originalstocks.facedetectormlkit.Helper

import android.graphics.*
import com.originalstocks.facedetectionmlkit.helper.GraphicOverlay

class RectangleOverlay internal constructor(overlayHelper: GraphicOverlay,
                                            private val bound: Rect?) : GraphicOverlay.Graphic(overlayHelper){

    private val rectPaint: Paint

    init {
        rectPaint = Paint()
        rectPaint.color = Color.CYAN
        rectPaint.strokeWidth = 4.0f
        rectPaint.style = Paint.Style.STROKE

        postInvalidate()

    }

    override fun draw(canvas: Canvas) {

        val rect = RectF(bound)
        rect.left = translateX(rect.left)
        rect.right = translateX(rect.right)
        rect.top = translateY(rect.top)
        rect.bottom = translateY(rect.bottom)

        canvas.drawRect(rect, rectPaint)


    }
}