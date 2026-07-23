package com.neverhide.empire.launcher.shapes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** CIRCLE mode: single spinning ring in the XZ plane. */
object CircleLayout {
    fun place(count: Int): List<Vec3> {
        if (count == 0) return emptyList()
        val r = 2.8f
        return (0 until count).map {
            val a = (2 * PI * it / count).toFloat()
            Vec3(cos(a) * r, 0f, sin(a) * r)
        }
    }
}
