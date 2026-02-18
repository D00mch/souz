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
            id = "ru.gigadesk.mac-signing-conventions"
            implementationClass = "ru.gigadesk.buildlogic.MacSigningConventionsPlugin"
        }
        register("composeAppConventions") {
            id = "ru.gigadesk.compose-app-conventions"
            implementationClass = "ru.gigadesk.buildlogic.ComposeAppConventionsPlugin"
        }
    }
}
