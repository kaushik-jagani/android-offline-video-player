# ProGuard rules for Offline Video Player

# Keep ExoPlayer public APIs (allow R8 to optimize internals)
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.exoplayer.ExoPlayer { *; }
-keep class androidx.media3.exoplayer.ExoPlayer$Builder { *; }
-keep class androidx.media3.ui.PlayerView { *; }
-dontwarn androidx.media3.**

# Keep Room entities
-keep class com.example.videoplayer.data.model.** { *; }

# Keep Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Keep ViewBinding classes
-keep class com.example.videoplayer.databinding.** { *; }
