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

// 사용자 저장을 건드리지 않는 시작·저장·불러오기 실행 검사.
tasks.register<JavaExec>("verifyTitleSave") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.TitleSaveCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 원작 프롤로그의 실제 화면·음악 상태·종료·스킵 검사.
tasks.register<JavaExec>("verifyPrologue") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.PrologueCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 전체 재생 시간을 그대로 실행해 영상·음악의 동기와 자연 종료를 확인한다.
tasks.register<JavaExec>("verifyPrologueRealtime") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.PrologueCheck")
    args("--realtime")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}
