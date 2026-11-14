# ONNX Runtime Java API uses reflection/native bindings.
-keep class ai.onnxruntime.** { *; }

# Keep the Android application entry points and generated BuildConfig.
-keep class com.stemit.app.StemitApp { *; }
-keep class com.stemit.app.MainActivity { *; }

# Retain source/line information for useful crash diagnostics.
-keepattributes SourceFile,LineNumberTable
