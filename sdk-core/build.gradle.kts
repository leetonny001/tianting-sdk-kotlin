// 纯 Kotlin/JVM 的 SDK 核心：协议、连接、同步、发送、社交、本地库逻辑。不依赖 Android。
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

// JVM 上跑 21，产物是 Java 17 字节码，Android（AGP 8 脱糖）可以直接用。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.okhttp)

    testImplementation(project(":sdk-sqlite-jdbc"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.json.schema)
    testImplementation(libs.icu4j)
    testRuntimeOnly(libs.junit.launcher)
}

val generated = layout.projectDirectory.file("src/main/kotlin/com/tianting/sdk/ErrorCodes.gen.kt")
val errorsJson = rootProject.layout.projectDirectory.file("protocol/errors.json")

/** 把 errors.json 渲染成 Kotlin。只属于 console-server 的码不进终端 SDK（PRD-admin 10.1 节），与 sdk-ts 的生成器同一规则。 */
fun renderErrors(file: File): String {
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

    @Suppress("UNCHECKED_CAST")
    val spec = JsonSlurper().parse(file) as Map<String, List<Map<String, Any?>>>
    val errors = spec.getValue("errors").filter { e -> (e["scope"] as List<*>).any { it != "console" } }
    val out = StringBuilder()
    out.appendLine("// 由 ./gradlew :sdk-core:generateErrors 从 protocol/errors.json 生成，不要手改。")
    out.appendLine("package com.tianting.sdk")
    out.appendLine()
    out.appendLine("/** 错误码。 */")
    out.appendLine("public object ErrorCode {")
    errors.forEach { out.appendLine("    public const val ${it["name"]}: Int = ${it["code"]}") }
    out.appendLine("}")
    out.appendLine()
    out.appendLine("/** 错误码的详细信息：是否可以重试、给开发者的说明。 */")
    out.appendLine("internal val ERROR_INFO: Map<Int, ErrorInfo> = mapOf(")
    errors.forEach {
        out.appendLine("    ${it["code"]} to ErrorInfo(${q(it["name"] as String)}, ${it["retryable"]}, ${q(it["message"] as String)}),")
    }
    out.appendLine(")")
    out.appendLine()
    out.appendLine("/** WebSocket 关闭码。 */")
    out.appendLine("public object CloseCode {")
    spec.getValue("closeCodes").forEach { out.appendLine("    public const val ${it["name"]}: Int = ${it["code"]}") }
    out.appendLine("}")
    out.appendLine()
    out.appendLine("/** 关闭后是否应该自动重连（未列出的关闭码按可以重连处理）。 */")
    out.appendLine("internal val CLOSE_RECONNECT: Map<Int, Boolean> = mapOf(")
    spec.getValue("closeCodes").forEach { out.appendLine("    ${it["code"]} to ${it["reconnect"]},") }
    out.appendLine(")")
    out.appendLine()
    out.appendLine("/** 被踢原因 → 关闭码（null 表示保留值，一期不出现）。 */")
    out.appendLine("internal val KICK_CLOSE_CODE: Map<String, Int?> = mapOf(")
    spec.getValue("kickReasons").forEach { out.appendLine("    ${q(it["reason"] as String)} to ${it["closeCode"]},") }
    out.appendLine(")")
    return out.toString()
}

val generateErrors by tasks.registering {
    description = "从 protocol/errors.json 生成 ErrorCodes.gen.kt"
    inputs.file(errorsJson)
    doLast { generated.asFile.writeText(renderErrors(errorsJson.asFile)) }
}

val checkErrors by tasks.registering {
    description = "检查 ErrorCodes.gen.kt 与 protocol/errors.json 一致"
    inputs.file(errorsJson)
    inputs.file(generated)
    doLast {
        check(generated.asFile.readText() == renderErrors(errorsJson.asFile)) {
            "ErrorCodes.gen.kt is out of date; run ./gradlew :sdk-core:generateErrors"
        }
    }
}

tasks.named("check") { dependsOn(checkErrors) }

val integrationTest by tasks.registering(Test::class) {
    description = "对真实 im-server 的集成测试（需要 Go 和 tianting-server 源码）"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    environment("TIANTING_ROOT", rootProject.projectDir.absolutePath)
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
    environment("TIANTING_ROOT", rootProject.projectDir.absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
