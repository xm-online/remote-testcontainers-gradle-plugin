import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "com.xmedigital.gradle.remotetc"
version = "0.1.2"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val functionalTest: SourceSet by sourceSets.creating

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
dependencies {
    // the functional tests also reach the plugin's own (package-private) classes
    "functionalTestImplementation"(sourceSets.main.get().output)
}
configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

gradlePlugin {
    testSourceSets(functionalTest)
    website.set("https://github.com/xm-online/remote-testcontainers-gradle-plugin")
    vcsUrl.set("https://github.com/xm-online/remote-testcontainers-gradle-plugin.git")
    plugins {
        create("remoteTc") {
            id = "com.xmedigital.gradle.remotetc"
            implementationClass = "com.xmedigital.gradle.remotetc.RemoteTcPlugin"
            displayName = "Remote Testcontainers"
            description = "Runs Testcontainers-based tests against a container engine on a remote host over ssh"
            tags.set(listOf("testcontainers", "docker", "ssh", "remote", "testing"))
            compatibility {
                features {
                    configurationCache = true
                    isolatedProjects = false
                }
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("major13ua")
                    email.set("ievgen.chupryna@xmedigital.com")
                    organization.set("XME Digital")
                    organizationUrl.set("https://xmedigital.com")
                }
            }
            scm {
                url.set("https://github.com/xm-online/remote-testcontainers-gradle-plugin")
                connection.set("scm:git:https://github.com/xm-online/remote-testcontainers-gradle-plugin.git")
                developerConnection.set("scm:git:ssh://git@github.com/xm-online/remote-testcontainers-gradle-plugin.git")
            }
        }
    }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
}

tasks.check { dependsOn(functionalTestTask) }
tasks.withType<Test>().configureEach { useJUnitPlatform() }

// lint + vet gate: every compiler warning is an error
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
