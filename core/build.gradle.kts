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
    inputs.dir(rootProject.file("assets/data/events"))
    inputs.file(rootProject.file("docs/restoration/event-entry-native.json"))
    inputs.file(rootProject.file("docs/field-draw-order-native.json"))
    inputs.file(rootProject.file("docs/npc-native-verification.json"))
    inputs.file(rootProject.file("docs/buildings-native-verification.json"))
    inputs.file(rootProject.file("docs/sword-native-verification.json"))
    inputs.file(rootProject.file("docs/equipment-native-verification.json"))
    inputs.file(rootProject.file("docs/party-equipment-native.json"))
    inputs.dir(rootProject.file("assets/ui/equipment"))
    inputs.dir(rootProject.file("assets/opening"))
    inputs.dir(rootProject.file("assets/data/field"))
}
