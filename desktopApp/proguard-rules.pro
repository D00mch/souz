# Shrink only the statically referenced Compose icon catalog. Keep all other
# classes and members for reflection, Jackson, ServiceLoader, JNA and JNI.
-keep class !androidx.compose.material.icons.**,** { *; }
-keepattributes *
-dontoptimize
-dontobfuscate

# Kept libraries have optional integrations absent from the desktop classpath.
# They are neither shrunk nor optimized; keep warnings enabled for the icon code.
-dontwarn !androidx.compose.material.icons.**,**

-printusage build/compose/tmp/proguardReleaseJars/removed.txt
