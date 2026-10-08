# Stemit release rules.

# ONNX Runtime Java API uses reflection/native bindings.
-keep class ai.onnxruntime.** { *; }
