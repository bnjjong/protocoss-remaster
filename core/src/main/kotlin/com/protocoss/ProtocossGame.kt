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
import com.protocoss.dialogue.DialogueBox
import com.protocoss.dialogue.DialogueManager
import com.protocoss.dialogue.DialogueRepository
import com.protocoss.dialogue.FontManager
import com.protocoss.map.TileMap
import com.protocoss.map.TileMapRenderer
import com.protocoss.player.Player

/**
 * 프로토코스 리마스터 엔진의 메인 게임 라이프사이클을 관리합니다.
 *
 * 320x200 고정 종횡비 픽셀 뷰포트 내에서 16x16 필드 타일맵 렌더링, 플레이어 8방향 이동,
 * 부드러운 카메라 추적(Lerp Camera Follow) 및 한글 대화창 UI 인터프리터 시스템을 구동합니다.
 */
class ProtocossGame : ApplicationAdapter() {

    private lateinit var batch: SpriteBatch
    private lateinit var camera: OrthographicCamera
    private lateinit var uiCamera: OrthographicCamera
    private lateinit var viewport: Viewport
    private lateinit var debugFont: BitmapFont

    private val backgroundTextures = mutableListOf<Texture>()
    private var currentBackgroundIndex = 0

    private var playerTexture: Texture? = null
    private lateinit var player: Player

    private var tilesetTexture: Texture? = null
    private var tileMap: TileMap? = null
    private var tileMapRenderer: TileMapRenderer? = null
    private var isTileMapMode: Boolean = true

    // 대화 및 스토리 스크립트 시스템
    private lateinit var fontManager: FontManager
    private lateinit var dialogueRepository: DialogueRepository
    private lateinit var dialogueManager: DialogueManager
    private lateinit var dialogueBox: DialogueBox
    private var currentDemoSegmentId = 0

    override fun create() {
        batch = SpriteBatch()
        camera = OrthographicCamera()
        uiCamera = OrthographicCamera().apply {
            setToOrtho(false, Constants.ORIGINAL_WIDTH.toFloat(), Constants.ORIGINAL_HEIGHT.toFloat())
        }
        viewport = FitViewport(
            Constants.ORIGINAL_WIDTH.toFloat(),
            Constants.ORIGINAL_HEIGHT.toFloat(),
            camera
        )
        viewport.apply()

        debugFont = BitmapFont()
        debugFont.color = Color.YELLOW

        fontManager = FontManager()
        dialogueRepository = DialogueRepository()
        loadDialogueScripts()
        dialogueManager = DialogueManager()
        dialogueBox = DialogueBox(fontManager)

        loadExtractedBackgrounds()
        loadPlayerTexture()
        loadFieldMap()

        // 시작 위치: 벨슨의 오두막 중앙 (40x25 맵의 중심인 x=20, y=12 타일 영역)
        val initialX = 20f * TileMap.DEFAULT_TILE_SIZE
        val initialY = 12f * TileMap.DEFAULT_TILE_SIZE

        player = Player(
            initialX = initialX,
            initialY = initialY,
            texture = playerTexture
        )

        camera.position.set(player.x, player.y, 0f)
        camera.update()
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime
        handleInput(delta)
        updateCamera(delta)

        dialogueManager.update(delta)
        dialogueBox.update(delta)

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // 1단계: 월드 좌표계 렌더링 (필드 맵 및 플레이어)
        camera.update()
        batch.projectionMatrix = camera.combined

        batch.begin()

        if (isTileMapMode && tileMapRenderer != null) {
            // 1-A. 필드 타일맵 렌더링
            tileMapRenderer!!.render(batch, camera)
        } else if (backgroundTextures.isNotEmpty()) {
            // 1-B. 단일 배경 갤러리 렌더링
            val bg = backgroundTextures[currentBackgroundIndex]
            batch.draw(
                bg,
                camera.position.x - Constants.ORIGINAL_WIDTH / 2f,
                camera.position.y - Constants.ORIGINAL_HEIGHT / 2f,
                Constants.ORIGINAL_WIDTH.toFloat(),
                Constants.ORIGINAL_HEIGHT.toFloat()
            )
        }

        // 2. 플레이어 렌더링
        player.render(batch)

        batch.end()

        // 2단계: UI 정사영 화면 좌표계 렌더링 (대화창 및 조작 가이드)
        uiCamera.update()
        batch.projectionMatrix = uiCamera.combined

        if (dialogueManager.isActive) {
            dialogueBox.render(batch, dialogueManager)
        } else {
            batch.begin()
            val retroFont = fontManager.getFont(11)
            retroFont.color = Color(1f, 1f, 0.4f, 0.85f)
            retroFont.draw(batch, "[T / Space]: 대화 시작 | [WASD]: 이동 | [Shift]: 대시 | [M]: 타일맵 토글", 8f, 192f)
            batch.end()
        }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
    }

    override fun dispose() {
        batch.dispose()
        debugFont.dispose()
        fontManager.dispose()
        dialogueBox.dispose()
        playerTexture?.dispose()
        tilesetTexture?.dispose()
        backgroundTextures.forEach { it.dispose() }
        backgroundTextures.clear()
    }

    /**
     * 1단계 파이프라인에서 복호화된 대화 스크립트 JSON을 로드합니다.
     */
    private fun loadDialogueScripts() {
        val scriptFile = Gdx.files.internal("assets/data/scripts/area00_scripts.json")
        if (scriptFile.exists()) {
            dialogueRepository.loadFromJson(scriptFile.readString("UTF-8"))
        }
    }

    /**
     * 필드 맵 모델 및 타일셋 텍스처를 로드합니다.
     */
    private fun loadFieldMap() {
        val tilesetFile = Gdx.files.internal("assets/sprites/maps/area00_tileset.png")
        val mapFile = Gdx.files.internal("assets/data/maps/area00_map.json")
        if (tilesetFile.exists()) {
            tilesetTexture = Texture(tilesetFile).apply {
                setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
            }

            val map = if (mapFile.exists()) {
                TileMap.fromJson(mapFile.readString("UTF-8"))
            } else {
                TileMap(width = 60, height = 40)
            }

            tileMap = map
            tileMapRenderer = TileMapRenderer(map, tilesetTexture!!)
            isTileMapMode = true
        }
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
     * 키보드 입력을 처리하여 플레이어 이동, 대화 진행 및 시스템 조작을 수행합니다.
     */
    private fun handleInput(delta: Float) {
        // 대화창이 열려 있는 경우: 대화 조작만 처리하고 플레이어 이동 잠금
        if (dialogueManager.isActive) {
            player.update(delta, false, false, false, false, false)

            if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE) ||
                Gdx.input.isKeyJustPressed(Input.Keys.ENTER) ||
                Gdx.input.isKeyJustPressed(Input.Keys.E)
            ) {
                dialogueManager.advance()
            }
            return
        }

        val up = Gdx.input.isKeyPressed(Input.Keys.UP) || Gdx.input.isKeyPressed(Input.Keys.W)
        val down = Gdx.input.isKeyPressed(Input.Keys.DOWN) || Gdx.input.isKeyPressed(Input.Keys.S)
        val left = Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A)
        val right = Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D)
        val isDash = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)

        player.update(delta, up, down, left, right, isDash)

        // T 키 또는 Space 키: 스토리 대화창 열기 (지역 00 세그먼트 데모)
        if (Gdx.input.isKeyJustPressed(Input.Keys.T) || Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            val segment = dialogueRepository.getSegment(currentDemoSegmentId)
            if (segment != null) {
                dialogueManager.startDialogue(segment)
                // 다음번에는 다음 대화 세그먼트(예: 0 -> 1 -> 2)를 시연
                currentDemoSegmentId = (currentDemoSegmentId + 1) % 3
            }
        }

        // M 키: 필드 타일맵 모드 ↔ 정적 배경 갤러리 모드 전환
        if (Gdx.input.isKeyJustPressed(Input.Keys.M)) {
            isTileMapMode = !isTileMapMode
        }

        // Tab: 배경 순환 전환 (갤러리 모드일 때)
        if (Gdx.input.isKeyJustPressed(Input.Keys.TAB)) {
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
