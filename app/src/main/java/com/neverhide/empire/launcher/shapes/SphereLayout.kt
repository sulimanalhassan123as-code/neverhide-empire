package com.neverhide.empire.launcher.shapes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** SPHERE mode: even distribution using the Fibonacci sphere algorithm. */
object SphereLayout {
    fun place(count: Int): List<Vec3> {
        if (count == 0) return emptyList()
        val r = 2.8f
        val phi = PI * (3.0 - sqrt(5.0)) // golden angle
        return (0 until count).map { i ->
            val y = 1f - (i / (count - 1f).coerceAtLeast(1f)) * 2f
            val rad = sqrt(1f - y * y)
            val theta = (phi * i).toFloat()
            Vec3(cos(theta) * rad * r, y * r, sin(theta) * rad * r)
        }
    }
}
