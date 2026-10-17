# Keep sora-editor (heavy reflection + JNI via tm4e/oniguruma bindings)
-keep class io.github.rosemoe.sora.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keep class org.tukaani.xz.** { *; }

# Kotlin coroutines / metadata used by the editor's event system
-dontwarn kotlinx.coroutines.**

# R8: sora editor's ShareableData$DefaultImpls references kotlin.Cloneable$DefaultImpls
# (a Kotlin-internal class absent at runtime) — safe to ignore.
-dontwarn kotlin.Cloneable$DefaultImpls
