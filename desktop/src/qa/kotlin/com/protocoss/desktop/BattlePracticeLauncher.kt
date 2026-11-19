package com.protocoss.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.protocoss.battle.BattlePracticeScreen

object BattlePracticeLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        Lwjgl3Application(BattlePracticeScreen("종료") { Gdx.app.exit() }, Lwjgl3ApplicationConfiguration().apply {
            setTitle("연습 전투")
            setWindowedMode(720, 420)
            useVsync(true)
        })
    }
}
