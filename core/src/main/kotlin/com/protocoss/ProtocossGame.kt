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
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import com.badlogic.gdx.utils.viewport.Viewport
import com.protocoss.player.Player

/**
 * 프로토코스 리마스터 엔진의 메인 게임 라이프사이클을 관리합니다.
 *
 * 320x200 고정 종횡비 픽셀 뷰포트 내에서 필드 배경 렌더링, 플레이어 8방향 이동 및
 * 부드러운 카메라 추적(Lerp Camera Follow) 시스템을 구동합니다.
 */
class ProtocossGame : ApplicationAdapter() {

    private lateinit var batch: SpriteBatch
    private lateinit var camera: OrthographicCamera
    private lateinit var viewport: Viewport
    private lateinit var debugFont: BitmapFont

    private val backgroundTextures = mutableListOf<Texture>()
    private var currentBackgroundIndex = 0

    private var playerTexture: Texture? = null
    private lateinit var player: Player

    override fun create() {
        batch = SpriteBatch()
        camera = OrthographicCamera()
        viewport = FitViewport(
            Constants.ORIGINAL_WIDTH.toFloat(),
            Constants.ORIGINAL_HEIGHT.toFloat(),
            camera
        )
        viewport.apply()

        debugFont = BitmapFont()
        debugFont.color = Color.YELLOW

        loadExtractedBackgrounds()
        loadPlayerTexture()

        // 화면 중앙에 플레이어 초기 배치
        player = Player(
            initialX = Constants.ORIGINAL_WIDTH / 2f,
            initialY = Constants.ORIGINAL_HEIGHT / 2f,
            texture = playerTexture
        )

        camera.position.set(player.x, player.y, 0f)
        camera.update()
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime
        handleInput(delta)
        updateCamera(delta)

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        camera.update()
        batch.projectionMatrix = camera.combined

        batch.begin()

        // 1. 배경 렌더링
        if (backgroundTextures.isNotEmpty()) {
            val bg = backgroundTextures[currentBackgroundIndex]
            batch.draw(
                bg,
                0f,
                0f,
                Constants.ORIGINAL_WIDTH.toFloat(),
                Constants.ORIGINAL_HEIGHT.toFloat()
            )
        }

        // 2. 플레이어 렌더링
        player.render(batch)

        batch.end()
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
    }

    override fun dispose() {
        batch.dispose()
        debugFont.dispose()
        playerTexture?.dispose()
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
                    val texture = Texture(fileHandle).apply {
                        setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                    }
                    backgroundTextures.add(texture)
                } catch (e: Exception) {
                    Gdx.app.error("ProtocossGame", "Failed to load background: ${fileHandle.name()}", e)
                }
            }
        }
    }

    /**
     * 플레이어(카만) 스프라이트 텍스처를 로드합니다.
     */
    private fun loadPlayerTexture() {
        val spriteFile = Gdx.files.internal("assets/sprites/kaman_test.png")
        if (spriteFile.exists()) {
            playerTexture = Texture(spriteFile).apply {
                setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
            }
        }
    }

    /**
     * 플레이어를 중심으로 부드럽게 카메라를 이동(Lerp 보간)시킵니다.
     */
    private fun updateCamera(delta: Float) {
        val targetX = player.x
        val targetY = player.y

        val lerpFactor = MathUtils.clamp(delta * CAMERA_LERP_SPEED, 0f, 1f)
        camera.position.x += (targetX - camera.position.x) * lerpFactor
        camera.position.y += (targetY - camera.position.y) * lerpFactor
    }

    /**
     * 키보드 입력을 처리하여 플레이어 이동 및 시스템 조작을 수행합니다.
     */
    private fun handleInput(delta: Float) {
        val up = Gdx.input.isKeyPressed(Input.Keys.UP) || Gdx.input.isKeyPressed(Input.Keys.W)
        val down = Gdx.input.isKeyPressed(Input.Keys.DOWN) || Gdx.input.isKeyPressed(Input.Keys.S)
        val left = Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A)
        val right = Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D)
        val isDash = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)

        player.update(delta, up, down, left, right, isDash)

        // Tab 또는 스페이스: 배경 순환 전환
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB) || Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            if (backgroundTextures.isNotEmpty()) {
                currentBackgroundIndex = (currentBackgroundIndex + 1) % backgroundTextures.size
            }
        }

        // F11: 창 모드 ↔ 전체화면 토글
        if (Gdx.input.isKeyJustPressed(Input.Keys.F11)) {
            if (Gdx.graphics.isFullscreen) {
                Gdx.graphics.setWindowedMode(Constants.WINDOW_WIDTH, Constants.WINDOW_HEIGHT)
            } else {
                Gdx.graphics.setFullscreenMode(Gdx.graphics.displayMode)
            }
        }
    }

    companion object {
        /** 카메라 추적 선형 보간 속도 계수 */
        private const val CAMERA_LERP_SPEED: Float = 8f
    }
}
