package com.neverhide.empire.wallpaper.effects

/** Common interface for every 3D live-wallpaper effect. */
interface Effect {
    /** Called once when the GL context is ready. */
    fun init()
    /** Called on surface size change. */
    fun resize(width: Int, height: Int)
    /** Draw one frame. [time] is elapsed seconds. */
    fun draw(time: Float)
}
