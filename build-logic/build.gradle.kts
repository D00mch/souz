plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

gradlePlugin {
    plugins {
        register("macSigningConventions") {
            id = "ru.souz.mac-signing-conventions"
            implementationClass = "ru.souz.buildlogic.MacSigningConventionsPlugin"
        }
        register("composeAppConventions") {
            id = "ru.souz.compose-app-conventions"
            implementationClass = "ru.souz.buildlogic.ComposeAppConventionsPlugin"
        }
    }
}
