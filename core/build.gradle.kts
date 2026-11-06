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
