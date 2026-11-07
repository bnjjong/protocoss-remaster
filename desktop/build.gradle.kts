plugins {
    kotlin("jvm")
    application
}

val gdxVersion = "1.12.1"

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-desktop")
}

application {
    mainClass.set("com.protocoss.desktop.DesktopLauncher")
}

kotlin {
    jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    // macOS required JVM arguments for LWJGL3
    jvmArgs = listOf(
        "-XstartOnFirstThread"
    )
}

// 일반 단위 검사와 분리해 실제 그래픽 실행 환경에서만 실행한다.
val qa by sourceSets.creating {
    compileClasspath += sourceSets["main"].runtimeClasspath
    runtimeClasspath += output + compileClasspath
}

tasks.register<JavaExec>("verifyFieldBounds") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.FieldBoundsCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}
