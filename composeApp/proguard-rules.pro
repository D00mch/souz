-dontshrink
-dontoptimize
-dontpreverify

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,KotlinMetadata
# optional (debuggable stack traces):
# -keepattributes SourceFile,LineNumberTable

-keep class ru.souz.MainKt { public static void main(java.lang.String[]); }

# Only if something in this package is referenced by string/reflection:
# -keepnames class ru.souz.tool.** { *; }

# optional:
# -printmapping build/proguard/mapping.txt
