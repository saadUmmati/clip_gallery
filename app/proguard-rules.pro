# Keep ONNX Runtime classes and native libraries
-keep class ai.onnxruntime.** { *; }
-keep class ai.onnxruntime.OnnxTensor { *; }
-keep class ai.onnxruntime.OrtEnvironment { *; }
-keep class ai.onnxruntime.OrtSession { *; }
-dontwarn ai.onnxruntime.**
-keep class ai.onnxruntime.onnxruntime_models.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.onnxruntime_cpu.** { *; }
-keep class com.microsoft.onnxruntime.onnxruntime_gpu.** { *; }

# Keep Room entities and DAOs
-keep class com.example.gallery.app.data.db.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }
-keepclassmembers class * {
    @dagger.hilt.android.AndroidEntryPoint *;
}

# Keep ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(android.view.LayoutInflater);
    public static *** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static *** bind(android.view.View);
}

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { *; }

# Keep WorkManager and Hilt Workers
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.example.gallery.app.worker.** { *; }

# Keep HiltWorker assisted injection
-keep class * extends androidx.hilt.work.HiltWorkerFactory { *; }
-keep @androidx.hilt.work.HiltWorker class * { *; }
-keepclassmembers class * {
    @dagger.assisted.Assisted *;
    @dagger.assisted.AssistedInject <init>(...);
}

# Keep Hilt EntryPoints
-keep class com.example.gallery.app.OnnxEntryPoint { *; }
-dontwarn com.example.gallery.app.OnnxEntryPoint

# Keep Hilt generated classes
