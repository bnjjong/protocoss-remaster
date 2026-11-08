plugins {
    kotlin("jvm")
}

val gdxVersion = "1.12.1"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    api("com.badlogicgames.gdx:gdx:$gdxVersion")
    api("com.badlogicgames.gdx:gdx-freetype:$gdxVersion")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    inputs.file(rootProject.file("docs/npc-native-verification.json"))
    inputs.file(rootProject.file("docs/buildings-native-verification.json"))
    inputs.file(rootProject.file("docs/sword-native-verification.json"))
    inputs.dir(rootProject.file("assets/opening"))
    inputs.dir(rootProject.file("assets/data/field"))
}
