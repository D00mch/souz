# Tool schemas are derived from the `Input` data classes at runtime with kotlin-reflect, and their
# arguments are deserialised by Jackson. Both need the declarations, annotations and Kotlin metadata
# of those types intact, so R8 must not touch them.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class kotlin.Metadata { *; }

-keep class ru.souz.**$Input { *; }
-keep class ru.souz.**$Input$* { *; }
-keep enum ru.souz.** { *; }

# LLM request/response DTOs are mapped by Jackson field names.
-keep class ru.souz.llms.** { *; }
-keep class ru.souz.agent.** { *; }
-keep class ru.souz.db.** { *; }

# kotlin-reflect resolves these at runtime.
-keep class kotlin.reflect.jvm.internal.** { *; }
-dontwarn kotlin.reflect.**

-keep class com.fasterxml.jackson.module.kotlin.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.JsonProperty <fields>;
    @com.fasterxml.jackson.annotation.JsonProperty <methods>;
}
-dontwarn com.fasterxml.jackson.**

# Loaded through ServiceLoader, so nothing references them directly.
-keep class ru.souz.android.logging.AndroidSlf4jProvider { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }

-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.awt.**

# Logger names come from class names, and this device is debugged over adb. Keep the names of our
# own classes so logcat stays readable; they can still be shrunk away when unused.
-keepnames class ru.souz.** { *; }

# Tool parameters are declared by a property-targeted annotation that nothing references
# statically, so R8 is free to drop it. Then every property is skipped while building the schema
# and tools reach the model with no parameters at all, which shows up as calls with empty
# arguments. Keeping the annotation type keeps its uses.
-keep @interface ru.souz.tool.InputParamDescription
-keep class ru.souz.tool.** { *; }
-keepattributes AnnotationDefault
