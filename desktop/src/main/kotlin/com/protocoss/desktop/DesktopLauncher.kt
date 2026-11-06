package com.protocoss.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.protocoss.Constants
import com.protocoss.ProtocossGame

/**
 * macOS 및 데스크톱 환경용 LWJGL3 게임 애플리케이션 진입점입니다.
 *
 * 1280x800(320x200의 정수 4배) 윈도우 창 및 V-Sync 60fps 설정을 구성하고 게임 엔진을 기동합니다.
 */
object DesktopLauncher {

    /**
     * 데스크톱 메인 메서드입니다.
     *
     * [args] 실행 시 전달되는 커맨드라인 인자 목록입니다.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle(Constants.GAME_TITLE)
            setWindowedMode(Constants.WINDOW_WIDTH, Constants.WINDOW_HEIGHT)
            useVsync(true)
            setForegroundFPS(60)
            setResizable(true)
        }

        Lwjgl3Application(ProtocossGame(), config)
    }
}
