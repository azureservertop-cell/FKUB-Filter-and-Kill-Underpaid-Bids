// Pure Kotlin/JVM module: data model, parser, earnings rules, offer tracking.
// No Android dependency, unit-testable on any JVM; when Uber changes its UI only parser/adapter need updating.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}
