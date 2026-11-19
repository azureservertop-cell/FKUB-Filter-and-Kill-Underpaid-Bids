// 纯 Kotlin/JVM 模块：数据模型、解析器、收益规则、订单跟踪。
// 不依赖 Android，可在任何 JVM 上单元测试；Uber 改版时只需改 parser/adapter。
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
