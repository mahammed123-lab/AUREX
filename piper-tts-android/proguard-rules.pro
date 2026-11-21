# The sherpa-onnx native (.so) side calls back into these classes/methods by
# name via JNI, so they must survive R8/ProGuard minification untouched.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
