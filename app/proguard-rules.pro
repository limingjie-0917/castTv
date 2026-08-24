# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Media3 / ExoPlayer
-dontwarn com.google.android.exoplayer2.**
-keep class androidx.media3.** { *; }

# Keep our UPnP model classes (reflection-free, but be safe with data holders)
-keep class com.bd.casttv.dlna.** { *; }
