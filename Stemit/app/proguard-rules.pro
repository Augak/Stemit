# ONNX Runtime Java API uses reflection and native bindings.
-keep class ai.onnxruntime.** { *; }

# Retain the custom Application class.
-keep class com.stemit.app.StemitApp { *; }

# Keep source and line metadata for useful crash diagnostics.
-keepattributes SourceFile,LineNumberTable
