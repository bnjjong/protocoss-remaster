package com.protocoss

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
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
import com.protocoss.map.OriginalMapData
import com.protocoss.menu.GameMenu
import com.protocoss.menu.EquipmentRenderer
import com.protocoss.menu.FieldItems
import com.protocoss.menu.ItemTargetMenu
import com.protocoss.menu.EquipmentMenu
import com.protocoss.save.DosSave
import com.protocoss.save.SaveRepository
import com.protocoss.map.FieldBounds
import com.protocoss.map.TileMap
import com.protocoss.map.TileMapRenderer
import com.protocoss.field.FieldRules
import com.protocoss.field.FieldMovement
import com.protocoss.field.FieldNpcs
import com.protocoss.field.BuildingTravel
import com.protocoss.field.SwordStory
import com.protocoss.field.FieldDialogue
import com.protocoss.field.PlaceBanner
import com.protocoss.field.TownTravel
import com.protocoss.field.CabinDoors
import com.protocoss.player.Direction
import com.protocoss.player.Player
import com.protocoss.prologue.ProloguePlayer
import com.protocoss.opening.OpeningTimeline
import com.protocoss.opening.OpeningRenderer
import java.nio.file.Files
import java.nio.file.Path

/**
 * 프로토코스 리마스터 엔진의 메인 게임 라이프사이클을 관리합니다.
 *
 * 320x200 고정 종횡비 픽셀 뷰포트 내에서 원본 크기의 필드 타일맵 렌더링, 플레이어 8방향 이동,
 * 부드러운 카메라 추적(Lerp Camera Follow) 및 한글 대화창 UI 인터프리터 시스템을 구동합니다.
 */
class ProtocossGame(
    private val originalDirectory: Path? = defaultOriginalDirectory(),
    saveDirectory: Path = Path.of(System.getProperty("user.home"), "Library/Application Support/Protocoss/Saves")
) : ApplicationAdapter() {
    private enum class Scene { TITLE, PROLOGUE, OPENING, CLIP, FIELD, PAUSE, LOAD, SAVE, CONFIRM_SAVE, CONFIRM_TITLE, WEAPON, INVENTORY }
    private var scene = Scene.TITLE
    private var selected = 0
    private var loadReturn = Scene.TITLE
    private var entries = emptyList<SaveRepository.Entry>()
    private var message = "방향키 선택 · Enter 확인 · Esc 뒤로"
    private val saves = SaveRepository(saveDirectory, originalDirectory)
    private lateinit var menu: GameMenu
    private lateinit var equipmentRenderer:EquipmentRenderer
    private lateinit var equipmentSounds:Map<Int,com.badlogic.gdx.audio.Sound>
    private var equipmentMenu=EquipmentMenu()
    // 원작 0x7E510의 보행 속도. 저장 때 이벤트 변수 297로 옮긴다.
    private var fieldStep=1
    private var itemTarget:ItemTargetMenu?=null
    private var inventoryEquipment=false
    private var inventorySelection=0
    private var inventoryScroll=0
    private var session: DosSave? = null
    private var pendingSlot = 0
    private var prologue: ProloguePlayer? = null
    private var encounterClip: ProloguePlayer? = null
    private var clipFade=0f
    private var clipStarted=false
    private var clipReturning=false
    private var activeTrack: Int? = null
    private var placeBanner: PlaceBanner? = null
    private lateinit var opening: OpeningTimeline
    private lateinit var openingRenderer: OpeningRenderer
    private lateinit var fieldRules: FieldRules
    private lateinit var buildingTravel: BuildingTravel
    private lateinit var swordStory:SwordStory
    private var shopConversation=false
    private var swordReceived=false
    private lateinit var doorSound: com.badlogic.gdx.audio.Sound
    private var fieldMovement: FieldMovement? = null
    private var fieldNpcs: FieldNpcs? = null
    private var fieldDialogue: FieldDialogue? = null
    private val npcClock=FieldNpcs.Clock()
    private var fieldEvent: Int? = null
    private var eventDestination: DosSave? = null
    private var walkingTime = 0f
    private var fieldFade = 0f
    private var openingMusic: com.badlogic.gdx.audio.Music? = null
    private var resumeOpeningMusic = false

    private lateinit var batch: SpriteBatch
    private lateinit var camera: OrthographicCamera
    private lateinit var uiCamera: OrthographicCamera
    private lateinit var viewport: Viewport
    private lateinit var debugFont: BitmapFont

    private val backgroundTextures = mutableListOf<Texture>()
    private var currentBackgroundIndex = 0

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
        menu = GameMenu(fontManager)
        equipmentRenderer=EquipmentRenderer()
        equipmentSounds=listOf(8,12,13).associateWith { Gdx.audio.newSound(Gdx.files.internal("assets/audio/sfx/e${it.toString().padStart(4,'0')}.wav")) }
        dialogueRepository = DialogueRepository()
        loadDialogueScripts()
        dialogueManager = DialogueManager()
        dialogueBox = DialogueBox(fontManager)

        loadExtractedBackgrounds()
        opening = OpeningTimeline.parse(Gdx.files.internal("assets/opening/opening.json").readString("UTF-8"))
        openingRenderer = OpeningRenderer(opening)
        swordStory=SwordStory(opening.data.get("sword_story"))
        fieldRules = FieldRules(Gdx.files.internal("assets/data/field/field.json").readString()) {
            Gdx.files.internal("assets/data/field/$it").readBytes()
        }
        buildingTravel=BuildingTravel(Gdx.files.internal("assets/data/field/buildings.json").readString())
        doorSound=Gdx.audio.newSound(Gdx.files.internal("assets/audio/sfx/e0000.wav"))
        loadFieldMap()

        // 원본 맵의 실제 타일 크기로 오두막 중앙 위치를 계산한다.
        val initialX = tileMap?.totalPixelWidth?.times(0.5f) ?: (20f * TileMap.DEFAULT_TILE_SIZE)
        val initialY = tileMap?.totalPixelHeight?.times(0.5f) ?: (12f * TileMap.DEFAULT_TILE_SIZE)

        player = Player(
            initialX = initialX,
            initialY = initialY,
            texture = null
        )

        camera.position.set(player.x, player.y, 0f)
        updateFieldView(0f)
        camera.update()
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime
        val wasField = scene == Scene.FIELD
        if (Gdx.input.isKeyJustPressed(Input.Keys.F11)) toggleFullscreen()
        if (scene == Scene.PROLOGUE) {
            val movie = requireNotNull(prologue)
            if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) ||
                Gdx.input.isKeyJustPressed(Input.Keys.SPACE) ||
                Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) finishPrologue()
            else {
                movie.update(delta)
                if (movie.finished) finishPrologue()
            }
        } else if (scene == Scene.CLIP) {
            updateEncounterClip(delta)
        } else if (scene == Scene.OPENING) {
            if(opening.continuesField) fieldMovement?.update(delta,0,false,tick={ x,y -> fieldNpcs?.tick(x,y) }) { false }
            opening.update(delta)
            openingRenderer.update(delta)
            if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE) || Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) opening.advance()
            if(opening.videoId!=null) {
                encounterClip=ProloguePlayer("assets/encounter/encounter.pvid","assets/opening/music-27/music.ogg")
                clipFade=0f;clipStarted=false;clipReturning=false;scene=Scene.CLIP
            }
            if (opening.finished) {
                val wasEvent=fieldEvent!=null
                val destination=eventDestination ?: DosSave.afterOpening(Gdx.files.internal("assets/data/original/start.dat").readBytes())
                fieldEvent=null;eventDestination=null
                enterField(destination,preserveField=opening.continuesField)
                val track=destination.variable(4).takeIf { it in setOf(10,24,25) } ?: 25
                if(!wasEvent || openingMusic==null || activeTrack!=track) playOpeningMusic(track)
                fieldFade=if(opening.continuesField) 0f else 1f
            }
        } else if (scene == Scene.FIELD) {
            if(fieldFade>0f) {
                fieldMovement?.update(delta,0,false,tick={ x,y -> fieldNpcs?.tick(x,y) }) { false }
                fieldFade=(fieldFade-delta*70f/64f).coerceAtLeast(0f)
            }
            else if(placeBanner!=null) {
                val banner=placeBanner!!
                fieldMovement?.update(delta,0,false,tick={ x,y -> fieldNpcs?.tick(x,y) }) { false }
                if(Gdx.input.isKeyJustPressed(Input.Keys.ANY_KEY)) banner.advance()
                banner.update(delta)
                openingRenderer.update(delta)
                if(banner.finished) placeBanner=null
            } else if (fieldDialogue==null && Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
                scene = Scene.PAUSE
                selected = 0
                message = "방향키 선택 · Enter 확인 · Esc 계속"
            } else {
                handleInput(delta)
                if(fieldDialogue==null) updateFieldView(delta)
                dialogueManager.update(delta)
                dialogueBox.update(delta)
                openingRenderer.update(delta)
                if (player.isMoving) walkingTime += delta else walkingTime = 0f
            }
        } else if(scene==Scene.WEAPON) handleWeaponInput()
        else if(scene==Scene.INVENTORY) handleInventoryInput()
        else handleMenuInput()
        if(scene == Scene.TITLE && openingMusic != null) { openingMusic?.dispose();openingMusic=null }
        // 메뉴 확인에 쓴 Enter·Space가 필드의 대화·이동으로 전달되지 않는다.
        if (!wasField && scene == Scene.FIELD) updateFieldView(0f)

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // 창 크기·전체화면 전환이 화면 영역을 바꿔도 매 프레임 원래 표시 영역을 복구한다.
        viewport.apply()
        if (scene != Scene.FIELD) {
            uiCamera.update()
            batch.projectionMatrix = uiCamera.combined
            if (scene == Scene.PROLOGUE) prologue!!.render(batch)
            else if (scene == Scene.OPENING) { batch.begin(); openingRenderer.render(batch,if(opening.continuesField) fieldNpcs?.actors.orEmpty() else emptyList()); batch.end() }
            else if (scene == Scene.CLIP) {
                if(clipStarted && !clipReturning) encounterClip!!.render(batch)
                else { batch.begin();openingRenderer.render(batch);openingRenderer.fade(batch,clipFade);batch.end() }
            }
            else renderMenu()
            return
        }

        // 1단계: 월드 좌표계 렌더링 (필드 맵 및 플레이어)
        batch.projectionMatrix = camera.combined

        batch.begin()

        if (isTileMapMode && tileMapRenderer != null) {
            // 1-A. 필드 타일맵 렌더링
            if(session?.area==0 && session?.map in 0..12) openingRenderer.renderMap(batch,session!!.map,0f,tileMap!!.totalPixelHeight-200f,true)
            else tileMapRenderer!!.render(batch, camera)
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
        val mapHeight = tileMap?.totalPixelHeight ?: 200f
        val mapNumber = session?.map
        if (session?.area == 0 && mapNumber in 0..12) {
            val npcs=fieldNpcs
            if(npcs!=null) npcs.actors.forEach { npc ->
                openingRenderer.renderActor(batch,npc.sprite,npc.frame,npc.x.toFloat(),200f-mapHeight+npc.y)
                openingRenderer.coverActor(batch,mapNumber!!,npc.x.toFloat(),npc.y.toFloat(),0f,mapHeight-200f,npc.mask)
            } else opening.data.get("maps").get(mapNumber.toString()).get("npcs").forEach { npc ->
                openingRenderer.renderActor(batch,npc.getInt("sprite"),npc.getInt("frame"),npc.getFloat("x"),200f-mapHeight+npc.getFloat("y"))
                openingRenderer.coverActor(batch,mapNumber!!,npc.getFloat("x"),npc.getFloat("y"),0f,mapHeight-200f,npc.getInt("mask"))
            }
        }
        val base = when(player.direction) {
            com.protocoss.player.Direction.NORTH -> 0
            com.protocoss.player.Direction.NORTH_EAST, com.protocoss.player.Direction.EAST, com.protocoss.player.Direction.SOUTH_EAST -> 4
            com.protocoss.player.Direction.SOUTH -> 8
            else -> 12
        }
        openingRenderer.renderActor(batch,0,(if(player.isMoving) base+(walkingTime*9).toInt()%4 else fieldMovement?.pose ?: base),player.x-12f,188f-player.y)
        if (session?.area == 0 && mapNumber in 0..12)
            openingRenderer.coverActor(batch,mapNumber!!,player.x-12f,mapHeight-player.y-12f,0f,mapHeight-200f,fieldMovement?.layer ?: 0)

        batch.end()

        // 2단계: UI 정사영 화면 좌표계 렌더링 (대화창 및 조작 가이드)
        uiCamera.update()
        batch.projectionMatrix = uiCamera.combined

        if (dialogueManager.isActive) {
            dialogueBox.render(batch, dialogueManager)
        }
        fieldDialogue?.takeIf { fieldDialogueReady() }?.let { dialogue ->
            batch.begin()
            openingRenderer.renderFieldDialogue(batch,session!!.map,camera.position.x-160f,mapHeight-camera.position.y-100f,
                fieldNpcs!!.actors,player.x-12f,mapHeight-player.y-12f,fieldMovement!!.pose,fieldMovement!!.layer,
                dialogue.page,dialogue.visibleCharacters,dialogue.rewardX)
            batch.end()
        }
        placeBanner?.takeIf { fieldFade<=0f }?.let { banner ->
            batch.begin()
            val height=tileMap!!.totalPixelHeight
            openingRenderer.renderPlaceBanner(batch,session!!.map,camera.position.x-160f,height-camera.position.y-100f,
                banner.x,banner.name,player.x-12f,height-player.y-12f,fieldMovement!!.pose,fieldMovement!!.layer,fieldNpcs?.actors)
            batch.end()
        }
        if(fieldFade>0f) { batch.begin();openingRenderer.fade(batch,fieldFade);batch.end() }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
        updateFieldView(0f)
        camera.update()
    }

    override fun pause() {
        prologue?.pause()
        encounterClip?.pause()
        resumeOpeningMusic = openingMusic?.isPlaying == true
        openingMusic?.pause()
    }
    override fun resume() {
        prologue?.resume()
        encounterClip?.resume()
        if(resumeOpeningMusic) openingMusic?.play()
        resumeOpeningMusic = false
    }

    override fun dispose() {
        prologue?.dispose()
        encounterClip?.dispose()
        openingRenderer.dispose()
        doorSound.dispose()
        openingMusic?.dispose()
        batch.dispose()
        menu.dispose()
        equipmentRenderer.dispose()
        equipmentSounds.values.forEach { it.dispose() }
        debugFont.dispose()
        fontManager.dispose()
        dialogueBox.dispose()
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
     * 맵 경계 안에서 캐릭터 위치를 제한하고 카메라가 부드럽게 뒤따르게 한다.
     */
    private fun updateFieldView(delta: Float) {
        val field = tileMap.takeIf { isTileMapMode }
        field?.let { FieldBounds.constrainPlayer(player, it) }
        val targetX = player.x
        val targetY = player.y

        val lerpFactor = MathUtils.clamp(delta * CAMERA_LERP_SPEED, 0f, 1f)
        camera.position.x += (targetX - camera.position.x) * lerpFactor
        camera.position.y += (targetY - camera.position.y) * lerpFactor
        // 갤러리에서 돌아왔거나 기존 카메라가 밖에 있어도 이번 프레임에 바로 복구한다.
        field?.let { FieldBounds.constrainCamera(camera, it) }
    }

    /**
     * 키보드 입력을 처리하여 플레이어 이동, 대화 진행 및 시스템 조작을 수행합니다.
     */
    private fun handleInput(delta: Float) {
        val npcs=fieldNpcs
        val movement=fieldMovement.takeIf { isTileMapMode }
        val talkKey=Gdx.input.isKeyJustPressed(Input.Keys.SPACE) || Gdx.input.isKeyJustPressed(Input.Keys.ENTER) || Gdx.input.isKeyJustPressed(Input.Keys.E)
        if(fieldDialogue==null && npcs!=null && movement?.atRest==true && talkKey) {
            val index=npcs.talkTarget(movement.x,movement.y,movement.pose,movement.layer)
            if(index!=null) {
                val event=npcs.actors[index].event
                val shop=swordStory.shopPages(session!!,event)
                val pages=shop ?: FieldDialogue.pages(opening.data,event,session!!.variable(8))
                if(pages!=null && pages.size>0) {
                    movement.faceForTalk()
                    npcs.beginTalk(index,movement.pose)
                    fieldDialogue=FieldDialogue(index,pages)
                    shopConversation=shop!=null;swordReceived=false
                    session=session!!.calledEvent(event)
                }
            }
        } else if(fieldDialogue!=null && fieldDialogueReady() && talkKey) {
            fieldDialogue!!.advance()
            if(fieldDialogue!!.finished) {
                if(fieldDialogue!!.npcIndex>=0) npcs!!.endTalk()
                if(shopConversation) session=swordStory.finishShop(session!!)
                shopConversation=false;swordReceived=false
                fieldDialogue=null
                player.setMotion(null,false)
                return
            }
        }
        if(fieldDialogue!=null) {
            if(shopConversation && fieldDialogue!!.isReward && !swordReceived) {
                session=session!!.receiveRepairedSword();swordReceived=true
            }
            movement!!.update(delta,0,false,tick={ x,y -> npcs!!.tick(x,y) }) { false }
            if(fieldDialogueReady()) fieldDialogue!!.update(delta)
            player.setMotion(null,false)
            return
        }
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

        if(movement == null) player.update(delta,up,down,left,right,isDash)
        else {
            val height=tileMap!!.totalPixelHeight
            val px=player.x-12f;val py=height-player.y-12f
            if(kotlin.math.abs(px-movement.x)>.01f || kotlin.math.abs(py-movement.y)>.01f) movement.place(px,py)
            val input=(if(up && !down) 1 else if(down && !up) 4 else 0) or
                (if(right && !left) 2 else if(left && !right) 8 else 0)
            val travelled=movement.update(delta,input,isDash,tick={ x,y -> npcs?.tick(x,y) }) { event -> handleFieldEvent(movement,event) }
            if(travelled) return
            player.setPosition(movement.x+12f,height-movement.y-12f)
            player.setMotion(Direction.fromInput(FieldRules.dy(movement.direction)<0,FieldRules.dy(movement.direction)>0,
                FieldRules.dx(movement.direction)<0,FieldRules.dx(movement.direction)>0),movement.moving)
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
    }

    private fun handleFieldEvent(movement: FieldMovement, event: Int): Boolean {
        val current=requireNotNull(session)
        if(swordStory.joins(current,event)) {
            startFieldEvent(5,swordStory.joinDestination(current))
            return true
        }
        if(current.map==4 && current.variable(0)==3 && event==15) session=current.calledEvent(15)
        if(current.map==4 && current.variable(0)==3 && event==9 && current.variable(8)<3) {
            movement.face(1)
            player.setMotion(Direction.NORTH,false)
            fieldDialogue=FieldDialogue(-1,opening.data.get("building_dialogues").get("locked"))
            session=current.calledEvent(event)
            return true
        }
        if(current.map==0 && event==0 && current.variable(0)==1 && !movement.doorOpen) {
            movement.doorOpen=true
            session=current.calledEvent(0)
            openingRenderer.openCabinDoor(fieldRules.data.get("door_changes"))
        }
        if(current.map==2 && event==16 && current.variable(6)==0) {
            startFieldEvent(2,current.travel(1,0,0,72,56,10,0,mapOf(1 to 17,4 to 25,6 to 1)))
            return true
        }
        if(current.map in setOf(4,12) && current.variable(0)==3 && (event==0 || event==9)) {
            val direction=if(movement.direction and 1!=0) 1 else movement.direction
            val changes=fieldRules.openDoor(current.map,movement.x,movement.y,direction)
            if(changes!=null) {
                openingRenderer.changeCells(current.map,changes)
                fieldNpcs?.changeCells(changes)
                session=current.calledEvent(event)
                movement.face(direction)
                player.setMotion(Direction.NORTH,false)
                doorSound.play()
                return true
            }
        }
        val building=buildingTravel.travel(current,event)
        val town=TownTravel.travel(current,event)
        if(current.map==3 && event==2 && current.variable(7)==0) {
            startFieldEvent(4,current.travel(3,0,0,232,120,8,0,mapOf(1 to 3,7 to 1)))
            return true
        }
        val destination=building ?: town ?: CabinDoors.travel(current,event) ?: return false
        if(building!=null && buildingTravel.hasDoorSound(current,event)) doorSound.play()
        if(town==null && current.map==0 && current.variable(8)==2) startFieldEvent(3,destination)
        else {
            enterField(destination,false)
            if(town!=null && (destination.map in setOf(4,12) || current.map in setOf(4,12))) placeBanner=PlaceBanner(destination.location)
            val track=destination.variable(4).takeIf { it in setOf(10,24,25) } ?: 25
            if(openingMusic==null || activeTrack!=track) playOpeningMusic(track)
            fieldFade=1f
        }
        return true
    }

    private fun toggleFullscreen() {
        if (Gdx.graphics.isFullscreen) Gdx.graphics.setWindowedMode(Constants.WINDOW_WIDTH, Constants.WINDOW_HEIGHT)
        else Gdx.graphics.setFullscreenMode(Gdx.graphics.displayMode)
    }

    private fun fieldDialogueReady()=fieldDialogue?.let { it.npcIndex<0 || fieldNpcs?.talkReady==true } ?: false

    private fun renderMenu() {
        if(scene==Scene.WEAPON) { equipmentRenderer.weapon(batch,session!!,equipmentMenu);return }
        if(scene==Scene.INVENTORY) { equipmentRenderer.inventory(batch,session!!,inventoryEquipment,inventorySelection,inventoryScroll,itemTarget);return }
        if (scene == Scene.TITLE) {
            menu.title(batch, selected)
            return
        }
        val rows = when (scene) {
            Scene.PAUSE -> listOf("계속하기", "저장하기", "불러오기", "메인 화면", "소지품", "장비")
            Scene.LOAD, Scene.SAVE -> entries.map {
                "${it.label}  ${it.error ?: it.save?.location?.take(17) ?: "빈 저장 공간"}"
            }
            Scene.CONFIRM_SAVE, Scene.CONFIRM_TITLE -> listOf("아니요", "예")
            else -> emptyList()
        }
        val title = when (scene) {
            Scene.LOAD -> "저장 지점 불러오기"
            Scene.SAVE -> "진행 저장하기"
            Scene.CONFIRM_SAVE -> "기존 저장을 덮어쓸까요?"
            Scene.CONFIRM_TITLE -> "저장하지 않은 진행을 끝낼까요?"
            else -> "일시 정지"
        }
        menu.list(batch, uiCamera.combined, title, rows, selected, message)
    }

    private fun handleMenuInput() {
        val count = when (scene) {
            Scene.TITLE, Scene.CONFIRM_SAVE, Scene.CONFIRM_TITLE -> 2
            Scene.PAUSE -> 6
            else -> entries.size
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            when (scene) {
                Scene.PAUSE -> scene = Scene.FIELD
                Scene.LOAD -> scene = loadReturn
                Scene.SAVE -> scene = Scene.PAUSE
                Scene.CONFIRM_SAVE -> scene = Scene.SAVE
                Scene.CONFIRM_TITLE -> scene = Scene.PAUSE
                else -> Unit
            }
            selected = 0
            message = "방향키 선택 · Enter 확인 · Esc 뒤로"
            return
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.UP) || Gdx.input.isKeyJustPressed(Input.Keys.W)) selected = (selected + count - 1) % count
        if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN) || Gdx.input.isKeyJustPressed(Input.Keys.S)) selected = (selected + 1) % count
        var clicked = false
        if (Gdx.input.justTouched() && Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            val x = (Gdx.input.x - viewport.screenX) * 320f / viewport.screenWidth
            val y = (Gdx.graphics.height - Gdx.input.y - viewport.screenY) * 200f / viewport.screenHeight
            val row = if (scene == Scene.TITLE) {
                when { x in 70f..220f && y in 37f..52f -> 0; x in 70f..220f && y in 15f..30f -> 1; else -> -1 }
            } else if (x in 8f..312f) ((170f - y) / 16f).toInt().takeIf { y <= 170f && y > 170f - count * 16f } ?: -1 else -1
            if (row in 0 until count) { selected = row; clicked = true }
        }
        if (!clicked && !Gdx.input.isKeyJustPressed(Input.Keys.ENTER) && !Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) return
        when (scene) {
            Scene.TITLE -> if (selected == 0) startNewGame() else openLoad(Scene.TITLE)
            Scene.PAUSE -> when (selected) {
                0 -> scene = Scene.FIELD
                1 -> {
                    if (dialogueManager.isActive || !isTileMapMode) message = "대화를 마치고 필드에서 저장해주세요."
                    else { entries = saves.localEntries(); scene = Scene.SAVE; selected = 0 }
                }
                2 -> openLoad(Scene.PAUSE)
                3 -> { scene = Scene.CONFIRM_TITLE; selected = 0 }
                4 -> { scene=Scene.INVENTORY;inventoryEquipment=false;inventorySelection=0;inventoryScroll=0 }
                5 -> {
                    val party=equipmentRenderer.model.party(session!!)
                    if(party.isNotEmpty()) { equipmentMenu=EquipmentMenu(initialCharacter=party.first());scene=Scene.WEAPON }
                    else message="참가 중인 파티원이 없습니다."
                }
            }
            Scene.LOAD -> loadSelected()
            Scene.SAVE -> {
                pendingSlot = selected
                if (Files.exists(entries[selected].file)) { scene = Scene.CONFIRM_SAVE; selected = 0 }
                else saveSelected()
            }
            Scene.CONFIRM_SAVE -> if (selected == 1) saveSelected() else { scene = Scene.SAVE; selected = pendingSlot }
            Scene.CONFIRM_TITLE -> {
                if (selected == 1) { dialogueManager.closeDialogue(); scene = Scene.TITLE; session = null }
                else scene = Scene.PAUSE
                selected = 0
            }
            else -> Unit
        }
    }

    private fun menuConfirmed()=Gdx.input.isKeyJustPressed(Input.Keys.ENTER) || Gdx.input.isKeyJustPressed(Input.Keys.SPACE)

    private fun handleWeaponInput() {
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            equipmentSounds.getValue(8).play()
            if(equipmentMenu.back()) { scene=Scene.PAUSE;selected=5 }
            return
        }
        val model=equipmentRenderer.model
        for((key,direction) in listOf(Input.Keys.UP to "up",Input.Keys.DOWN to "down",Input.Keys.LEFT to "left",Input.Keys.RIGHT to "right")) {
            if(Gdx.input.isKeyJustPressed(key)) { equipmentMenu.navigate(direction,model,session!!);equipmentSounds.getValue(12).play() }
        }
        if(menuConfirmed()) {
            val choices=model.candidates(session!!,equipmentMenu.character,equipmentMenu.slot)
            val blocked=equipmentMenu.stage==EquipmentMenu.Stage.SELECT && choices.isNotEmpty() && !model.canEquip(session!!,choices[equipmentMenu.selected].id,equipmentMenu.character,equipmentMenu.slot)
            equipmentSounds.getValue(if(blocked || (equipmentMenu.stage==EquipmentMenu.Stage.SELECT && choices.isEmpty()))13 else 8).play()
            session=equipmentMenu.confirm(model,session!!)
            equipmentMenu.stepChange?.let { fieldStep=it;fieldMovement?.baseStep=it }
        }
    }

    private fun handleInventoryInput() {
        val model=equipmentRenderer.model;val use=FieldItems(model);val target=itemTarget
        if(target!=null) {
            if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
                equipmentSounds.getValue(8).play();itemTarget=null
                val count=model.inventory(session!!,false).size
                if(count==0) { scene=Scene.PAUSE;selected=4 }
                inventorySelection=inventorySelection.coerceIn(0,(count-1).coerceAtLeast(0))
                inventoryScroll=inventoryScroll.coerceIn((inventorySelection-5).coerceAtLeast(0),inventorySelection)
                return
            }
            for((key,direction) in listOf(Input.Keys.UP to "up",Input.Keys.DOWN to "down",Input.Keys.LEFT to "left",Input.Keys.RIGHT to "right")) {
                if(Gdx.input.isKeyJustPressed(key)) { target.navigate(direction,model.party(session!!).size);equipmentSounds.getValue(12).play() }
            }
            // 원작 대상 선택은 Enter로 사용한다. Space는 목록 진입 때만 확인 키다.
            if(Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
                val result=use.use(session!!,target.item,model.party(session!!)[target.index])
                session=result.save;equipmentSounds.getValue(if(result.blocked)13 else 8).play()
            }
            return
        }
        if(Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { scene=Scene.PAUSE;selected=4;return }
        if(Gdx.input.isKeyJustPressed(Input.Keys.LEFT) || Gdx.input.isKeyJustPressed(Input.Keys.RIGHT)) {
            inventoryEquipment=!inventoryEquipment;inventorySelection=0;inventoryScroll=0
        }
        val choices=model.inventory(session!!,inventoryEquipment);val count=choices.size
        if(count==0) return
        if(Gdx.input.isKeyJustPressed(Input.Keys.UP)) inventorySelection=(inventorySelection-1).coerceAtLeast(0)
        if(Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) inventorySelection=(inventorySelection+1).coerceAtMost(count-1)
        inventoryScroll=inventoryScroll.coerceIn((inventorySelection-5).coerceAtLeast(0),inventorySelection)
        if(menuConfirmed() && !inventoryEquipment) {
            val id=choices[inventorySelection].id
            if(use.targetable(id)) { itemTarget=ItemTargetMenu(id);equipmentSounds.getValue(8).play() }
            else equipmentSounds.getValue(13).play()
        }
    }

    private fun openLoad(returnTo: Scene) {
        entries = saves.loadEntries()
        loadReturn = returnTo
        scene = Scene.LOAD
        selected = 0
        message = "방향키 선택 · Enter 불러오기 · Esc 뒤로"
    }

    private fun playOpeningMusic(track: Int) {
        activeTrack=track
        openingMusic?.dispose()
        openingMusic=Gdx.audio.newMusic(Gdx.files.internal("assets/opening/music-${track.toString().padStart(2,'0')}/music.ogg")).apply {
            isLooping=true
            play()
        }
    }

    private fun startNewGame() {
        fieldStep=1
        openingMusic?.dispose();openingMusic=null
        var movie: ProloguePlayer? = null
        try {
            val save = DosSave.newGame(Gdx.files.internal("assets/data/original/start.dat").readBytes())
            movie = ProloguePlayer()
            enterField(save)
            currentDemoSegmentId = 0
            prologue = movie
            scene = Scene.PROLOGUE
            movie.play()
        } catch (e: Exception) {
            movie?.dispose()
            prologue = null
            scene = Scene.TITLE
            showMenuError("새 게임을 시작할 수 없습니다", e)
        }
    }

    private fun updateEncounterClip(delta: Float) {
        val clip=requireNotNull(encounterClip)
        if(!clipStarted) {
            clipFade=(clipFade+delta*70f/64f).coerceAtMost(1f)
            if(clipFade==1f) { openingMusic?.pause();clipStarted=true;clip.play() }
        } else if(!clip.finished) clip.update(delta)
        else {
            if(!clipReturning) { clip.pause();playOpeningMusic(25);clipReturning=true;clipFade=1f }
            clipFade=(clipFade-delta*70f/64f).coerceAtLeast(0f)
            if(clipFade==0f) {
                clip.dispose();encounterClip=null;opening.resumeVideo();scene=Scene.OPENING
            }
        }
    }

    private fun startFieldEvent(program: Int, destination: DosSave) {
        val data=opening.data
        opening=OpeningTimeline(data,program,program)
        if(opening.continuesField) openingRenderer.continueField(opening)
        else { openingRenderer.dispose();openingRenderer=OpeningRenderer(opening) }
        val track=when(program) { 3->27;5->24;else->25 }
        if(openingMusic==null || activeTrack!=track) playOpeningMusic(track)
        fieldEvent=program;eventDestination=destination;scene=Scene.OPENING
        player.setMotion(null,false)
    }

    private fun finishPrologue() {
        prologue?.dispose()
        prologue = null
        openingRenderer.dispose()
        fieldEvent=null;eventDestination=null
        opening = OpeningTimeline.parse(Gdx.files.internal("assets/opening/opening.json").readString("UTF-8"))
        openingRenderer = OpeningRenderer(opening)
        scene = Scene.OPENING
        playOpeningMusic(2)
    }

    private fun loadSelected() {
        val entry = entries[selected]
        if (entry.save == null) { message = entry.error ?: "저장된 게임이 없습니다."; return }
        try {
            val loaded = saves.load(entry)
            openingMusic?.dispose();openingMusic=null
            enterField(loaded.save)
            loaded.save.variable(4).takeIf { it in setOf(10,24,25) }?.let { playOpeningMusic(it) }
            currentDemoSegmentId = loaded.nextDialogue
        } catch (e: Exception) {
            message = "불러오지 못했습니다. 원본 게임 경로와 파일을 확인해주세요."
            Gdx.app.error("Save", "불러오기 실패: ${entry.file}", e)
        }
    }

    private fun enterField(save:DosSave) {
        fieldStep=save.variable(297).takeIf { it in listOf(1,2,4) } ?: 1
        enterField(save,false)
    }

    private fun enterField(save: DosSave,preserveField:Boolean) {
        // 파일·맵·좌표를 모두 확인한 뒤에 현재 세션을 교체한다.
        val original = originalDirectory?.let { OriginalMapData.load(it, save.area, save.map) }
        require(original != null || (save.area == 0 && save.map in 0..12)) { "이 저장 지점에는 원본 MAP 데이터가 필요합니다." }
        val fallbackMap = if (save.map == 2) "new_game_map.json" else "area00_map.json"
        val map = original?.map ?: if(save.map in setOf(0,3,4,5,6,7,8,9,10,11,12)) {
            val source=opening.data.get("maps").get(save.map.toString())
            TileMap(source.getInt("width"),source.getInt("height"),8,8)
        } else TileMap.fromJson(Gdx.files.internal("assets/data/maps/$fallbackMap").readString())
        val x = save.worldX + 12f
        val y = save.worldY(map.totalPixelHeight) - 12f
        require(x in 0f..map.totalPixelWidth && y in 0f..map.totalPixelHeight) { "저장 위치가 맵 밖에 있습니다." }
        val texture = if (original != null) {
            val pixmap = Pixmap(original.atlasWidth, original.atlasHeight, Pixmap.Format.RGBA8888)
            try { pixmap.pixels.put(original.rgba).flip(); Texture(pixmap) } finally { pixmap.dispose() }
        } else Texture(Gdx.files.internal("assets/sprites/maps/area00_tileset.png"))
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        tilesetTexture?.dispose()
        tilesetTexture = texture
        tileMap = map
        tileMapRenderer = TileMapRenderer(map, texture)
        isTileMapMode = true
        dialogueManager.closeDialogue()
        player.setPosition(x, y)
        player.update(0f, false, false, false, false)
        camera.position.set((save.scrollX * 8 + 160).toFloat(), map.totalPixelHeight - (save.scrollY * 8 + 100), 0f)
        FieldBounds.constrainCamera(camera, map)
        session = save
        shopConversation=false;swordReceived=false
        if(!preserveField) fieldRules.reset(save.map)
        fieldDialogue=null
        if(!preserveField) fieldNpcs=if(save.area==0 && save.map in 4..12) FieldNpcs(opening.data.get("maps").get(save.map.toString()),npcClock) else null
        placeBanner=null
        if(!preserveField) openingRenderer.resetFieldMaps()
        fieldMovement = if(save.area==0 && save.map in 0..12) FieldMovement(fieldRules,save.map,save.layer.coerceIn(0,2),save.pose).apply {
            place(save.worldX,map.totalPixelHeight-save.worldY(map.totalPixelHeight))
            npcs=fieldNpcs
            baseStep=fieldStep
        } else null
        player.setMotion(when(save.pose/4) { 0 -> Direction.NORTH;1 -> Direction.EAST;2 -> Direction.SOUTH;else -> Direction.WEST },false)
        fieldFade=0f
        scene = Scene.FIELD
    }

    private fun saveSelected() {
        try {
            val pose=when(player.direction) { Direction.NORTH -> 0;Direction.EAST,Direction.NORTH_EAST,Direction.SOUTH_EAST -> 4;Direction.SOUTH -> 8;else -> 12 }
            val current = requireNotNull(session).withFieldState(fieldMovement?.layer ?: session!!.layer,fieldMovement?.pose ?: pose).atPosition(player.x - 12f, player.y + 12f, tileMap!!.totalPixelHeight, tileMap!!.totalPixelWidth)
            val stored=current.withVariables(mapOf(297 to fieldStep))
            saves.write(pendingSlot, stored, currentDemoSegmentId)
            session = stored
            scene = Scene.PAUSE
            selected = 0
            message = "저장 ${pendingSlot + 1}에 기록했습니다."
        } catch (e: Exception) {
            scene = Scene.SAVE
            selected = pendingSlot
            showMenuError("저장하지 못했습니다. 저장 공간을 확인해주세요.", e)
        }
    }

    private fun showMenuError(text: String, cause: Exception) {
        message = text
        if (scene == Scene.TITLE) { entries = saves.loadEntries(); scene = Scene.LOAD; loadReturn = Scene.TITLE; selected = 0 }
        Gdx.app.error("Save", text, cause)
    }

    companion object {
        fun defaultOriginalDirectory(): Path? {
            val explicit = System.getProperty("protocoss.gameDir") ?: System.getenv("PROTOCOSS_GAME_DIR")
            if (explicit != null) return Path.of(explicit)
            return Path.of(System.getProperty("user.home"), "Downloads/Protocoss/Protocoss_Game")
                .takeIf { Files.isDirectory(it.resolve("MAP")) }
        }

        /** 카메라 추적 선형 보간 속도 계수 */
        private const val CAMERA_LERP_SPEED: Float = 8f
    }
}
