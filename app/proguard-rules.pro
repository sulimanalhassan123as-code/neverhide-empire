# Keep GL renderer + service entry points reflected by the framework.
-keep class com.neverhide.empire.** { *; }
-keepclassmembers class * extends android.app.Service { public *; }
