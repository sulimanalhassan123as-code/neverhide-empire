# Neverhide Empire release hardening
# Aggressive minify+obfuscation makes reverse engineering far harder.

# Keep crash traces readable-ish for ourselves during testing
-keepattributes SourceFile,LineNumberTable

# Device admin receiver is referenced from the manifest (AGP keeps it),
# but keep it explicitly for safety — losing it kills the Guardian.
-keep class com.neverhide.empire.guardian.GuardianAdminReceiver { *; }

# Live wallpaper engine is referenced by WallpaperManager via component name
-keep class com.neverhide.empire.wallpaper.LiveWallpaperEngine { *; }

# Compose / Material
-dontwarn org.jetbrains.annotations.**

# Strip debug logging from release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
