package com.protocoss.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.protocoss.Constants
import com.protocoss.ProtocossGame
import java.nio.file.Path

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

        val options = args.associate { argument ->
            require(argument.startsWith("--game-dir=") || argument.startsWith("--save-dir=")) {
                "사용법: --game-dir=원본게임폴더 --save-dir=저장폴더"
            }
            argument.substringBefore('=') to argument.substringAfter('=')
        }
        val original = options["--game-dir"]?.let { Path.of(it) } ?: ProtocossGame.defaultOriginalDirectory()
        val saves = options["--save-dir"]?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), "Library/Application Support/Protocoss/Saves")
        Lwjgl3Application(ProtocossGame(original, saves), config)
    }
}
