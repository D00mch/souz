plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
    `maven-publish`
    signing
    alias(libs.plugins.mavenPublishBase)
}

group = "io.github.d00mch"
version = "2.0.0"

dependencies {
    api(libs.kotlinx.coroutines)
}

java {
    withSourcesJar()
    withJavadocJar()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}

// Verification repositories do not need release credentials or signatures.
signing { isRequired = providers.gradleProperty("souz.signPluginSdk").isPresent }

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
            pom {
                name.set("Souz JVM plugin API")
                description.set("Stateless JVM plugin contracts and invocation-bound host services for Souz.")
                url.set("https://github.com/D00mch/souz")
                licenses {
                    license {
                        name.set("Eclipse Public License 2.0")
                        url.set("https://www.eclipse.org/legal/epl-2.0/")
                    }
                }
                scm {
                    url.set("https://github.com/D00mch/souz")
                    connection.set("scm:git:https://github.com/D00mch/souz.git")
                }
                developers {
                    developer {
                        id.set("D00mch")
                        name.set("Artur Dumchev")
                        url.set("https://github.com/D00mch")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "verification"
            url = layout.buildDirectory.dir("maven-repository").get().asFile.toURI()
        }
    }
}
