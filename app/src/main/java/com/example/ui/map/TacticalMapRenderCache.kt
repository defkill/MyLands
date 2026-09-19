package com.example.ui.map

import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect

/**
 * Caches Paint, PathEffect, and Path objects to eliminate allocations during TacticalMapView draw passes.
 */
class TacticalMapRenderCache {
    // Paints
    val militaryGridPaint = AndroidPaint().apply {
        color = android.graphics.Color.argb(140, 129, 199, 132)
        textSize = 28f
        isAntiAlias = true
    }

    val sightLinePaint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(255, 213, 79)
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = AndroidPaint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val losElevationPaint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 30f
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = AndroidPaint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val triangulationPaint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(255, 138, 101)
        textSize = 26f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val routeLegPaint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 30f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val rulerPaint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
        isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val waypointNamePaint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 28f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val waypointNavPaint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(129, 212, 250)
        textSize = 24f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val candidatePointPaint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 28f
        isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val manualPositionPaint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(255, 183, 77)
        textSize = 24f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val contourTextPaint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(198, 150, 100)
        textSize = 22f
        isAntiAlias = true
        textAlign = AndroidPaint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    // PathEffects
    val trackDrDashEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 15f), 0f)
    val sightLineDashEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f)
    val triangulationDashEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f)
    val targetBearingDashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
    val candidateCircleDashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
    val manualCircleDashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

    // Reusable Paths (reset before each use)
    val reusablePath1 = Path()
    val reusablePath2 = Path()
}
