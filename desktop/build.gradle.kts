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

tasks.register<JavaExec>("verifyActorDepth") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.ActorDepthCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
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

// 시작 연출에서 첫 자유 이동까지 실제 입력·화면을 검사한다.
tasks.register<JavaExec>("verifyOpening") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.OpeningCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 패키지 자산만으로 충돌·문 왕복·저장 복귀를 실행한다.
tasks.register<JavaExec>("verifyFieldTravel") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.FieldTravelCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 원본 폴더 없는 조우 영상·마을 왕복과 저장 실행 검사.
tasks.register<JavaExec>("verifyTownTravel") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.TownTravelCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

tasks.register<JavaExec>("verifyNpcs") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.NpcCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

tasks.register<JavaExec>("verifyBuildings") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.BuildingCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

tasks.register<JavaExec>("verifySwordStory") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.SwordCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

tasks.register<JavaExec>("verifyEquipment") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.EquipmentCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

tasks.register<JavaExec>("verifyPartyEquipment") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.PartyEquipmentCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 자동 장비·해제의 원작 표본과 실제 입력·저장·화면을 비교한다.
tasks.register<JavaExec>("verifyAutomaticEquipment") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.AutomaticEquipmentCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 원본 폴더 없이 파티원 대상 소지품 사용·취소·저장을 실행한다.
tasks.register<JavaExec>("verifyFieldItems") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.FieldItemsCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 텐트의 거부·휴식 대기·음악 복귀·수량·저장을 실제 그래픽 환경에서 확인한다.
tasks.register<JavaExec>("verifyTent") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.TentCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 잡화점의 원작 거래 표본과 실제 입력·저장·그림을 비교한다.
tasks.register<JavaExec>("verifyShop") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.ShopCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 실내 주민 대화·여비 지급에서 실제 거래와 저장 재개까지 실행한다.
tasks.register<JavaExec>("verifyIndoor") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.IndoorCheck")
    if(project.hasProperty("indoorOnly")) args("--only-indoor")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 오두막 물체의 실제 조사 입력·벽난로 반응·저장 재개를 실행한다.
tasks.register<JavaExec>("verifyCabinInteraction") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.CabinInteractionCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 원본 문 명령의 실제 입력·저장·타일·통과 상태를 대조한다.
tasks.register<JavaExec>("verifyEventDoors") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.EventDoorsCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}

// 원작 보상의 모든 품목·수량/소지금 경계와 안내창을 실제 앱에서 확인한다.
tasks.register<JavaExec>("verifyEventRewards") {
    dependsOn(qa.classesTaskName)
    classpath = qa.runtimeClasspath
    mainClass.set("com.protocoss.desktop.EventRewardsCheck")
    workingDir = rootDir
    jvmArgs("-XstartOnFirstThread")
}
