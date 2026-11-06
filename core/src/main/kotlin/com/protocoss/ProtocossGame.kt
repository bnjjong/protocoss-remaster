package com.protocoss

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.viewport.FitViewport
import com.badlogic.gdx.utils.viewport.Viewport

/**
 * 프로토코스 리마스터 엔진의 메인 게임 라이프사이클을 관리합니다.
 *
 * 320x200 고정 종횡비 픽셀 뷰포트를 설정하고, 1단계에서 추출된 배경 및 컷씬 에셋을 렌더링하는 진입점입니다.
 */
class ProtocossGame : ApplicationAdapter() {

    private lateinit var batch: SpriteBatch
    private lateinit var camera: OrthographicCamera
    private lateinit var viewport: Viewport
    private lateinit var debugFont: BitmapFont

    private val backgroundTextures = mutableListOf<Texture>()
    private var currentBackgroundIndex = 0

    override fun create() {
        batch = SpriteBatch()
        camera = OrthographicCamera()
        viewport = FitViewport(
            Constants.ORIGINAL_WIDTH.toFloat(),
            Constants.ORIGINAL_HEIGHT.toFloat(),
            camera
        )
        viewport.apply()
        camera.position.set(
            Constants.ORIGINAL_WIDTH / 2f,
            Constants.ORIGINAL_HEIGHT / 2f,
            0f
        )
        camera.update()

        debugFont = BitmapFont()
        debugFont.color = Color.WHITE

        loadExtractedBackgrounds()
    }

    override fun render() {
        handleInput()

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        camera.update()
        batch.projectionMatrix = camera.combined

        batch.begin()
        if (backgroundTextures.isNotEmpty()) {
            val texture = backgroundTextures[currentBackgroundIndex]
            batch.draw(
                texture,
                0f,
                0f,
                Constants.ORIGINAL_WIDTH.toFloat(),
                Constants.ORIGINAL_HEIGHT.toFloat()
            )
        }
        batch.end()
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
    }

    override fun dispose() {
        batch.dispose()
        debugFont.dispose()
        backgroundTextures.forEach { it.dispose() }
        backgroundTextures.clear()
    }

    /**
     * 1단계 파이프라인에서 추출된 전투 배경 텍스처를 일괄 로드합니다.
     */
    private fun loadExtractedBackgrounds() {
        val backgroundsDir = Gdx.files.internal("assets/backgrounds")
        if (backgroundsDir.exists()) {
            val files = backgroundsDir.list { _, name -> name.endsWith(".png") }
            files.sortedBy { it.name() }.forEach { fileHandle ->
                try {
                    val texture = Texture(fileHandle)
                    // 픽셀 아트 뭉개짐 방지를 위한 Nearest 필터링
                    texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                    backgroundTextures.add(texture)
                } catch (e: Exception) {
                    Gdx.app.error("ProtocossGame", "Failed to load background: ${fileHandle.name()}", e)
                }
            }
        }
        Gdx.app.log("ProtocossGame", "Loaded ${backgroundTextures.size} backgrounds successfully.")
    }

    /**
     * 키보드 입력을 처리하여 배경 전환 및 전체화면 토글을 수행합니다.
     */
    private fun handleInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.RIGHT) || Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            if (backgroundTextures.isNotEmpty()) {
                currentBackgroundIndex = (currentBackgroundIndex + 1) % backgroundTextures.size
            }
        } else if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT)) {
            if (backgroundTextures.isNotEmpty()) {
                currentBackgroundIndex = if (currentBackgroundIndex - 1 < 0) {
                    backgroundTextures.size - 1
                } else {
                    currentBackgroundIndex - 1
                }
            }
        }

        // F11 또는 Command+F: 전체화면 전환
        if (Gdx.input.isKeyJustPressed(Input.Keys.F11)) {
            if (Gdx.graphics.isFullscreen) {
                Gdx.graphics.setWindowedMode(Constants.WINDOW_WIDTH, Constants.WINDOW_HEIGHT)
            } else {
                val displayMode = Gdx.graphics.displayMode
                Gdx.graphics.setFullscreenMode(displayMode)
            }
        }
    }
}
