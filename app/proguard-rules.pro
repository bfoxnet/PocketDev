# Keep sora-editor (heavy reflection + JNI via tm4e/oniguruma bindings)
-keep class io.github.rosemoe.sora.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keep class org.tukaani.xz.** { *; }

# Kotlin coroutines / metadata used by the editor's event system
-dontwarn kotlinx.coroutines.**
