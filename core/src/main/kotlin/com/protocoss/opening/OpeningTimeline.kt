package com.protocoss.opening

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/** AUTOANI의 이동·그리기·대화 대기를 순서대로 실행한다. 입력을 받을 때까지 다음 명령을 실행하지 않는다. */
class OpeningTimeline(val data: JsonValue, firstProgram: Int = 0, private val lastProgram: Int = 1, private val fieldMap:Int?=null) {
    data class Actor(var sprite: Int, var x: Float, var y: Float, var frame: Int,
                     var direction: Int = 0, var animate: Boolean = false, var speed: Int = 0, var mask: Int = 0)
    val actors = mutableListOf<Actor>()
    var program = firstProgram; private set
    val continuesField = data.get("programs")[firstProgram].getBoolean("continues_field",false)
    var map = 0; private set
    var scrollX = 0f; private set
    var scrollY = 0f; private set
    var finished = false; private set
    var videoId: String? = null; private set
    var dialogueId: Int? = null; private set
    var pageIndex = 0; private set
    val page: JsonValue? get() = dialogueId?.let { data.get("dialogues").get(it.toString())[pageIndex] }
    var visibleCharacters = 0; private set
    val pageComplete get() = page?.getString("text")?.length?.let { visibleCharacters >= it } ?: true
    private var cursor = 0
    private var cameraDirection = 0
    private var step = 1
    private var remainingTicks = 0
    private var accumulator = 0f
    private var textAccumulator = 0f
    var totalTicks = 0; private set
    private var fadeTicks = 0
    private var fadingOut = false
    val fadeAlpha get() = if(fadingOut) 1f-fadeTicks/64f else fadeTicks/64f

    init { loadProgram(); execute(); if(!continuesField) fadeTicks=64 }

    private fun loadProgram() {
        val current = data.get("programs")[program]
        map = if(continuesField && fieldMap!=null) fieldMap else current.getInt("map")
        scrollX = current.get("scroll")[0].asFloat(); scrollY = current.get("scroll")[1].asFloat()
        actors.clear(); cursor = 0; cameraDirection = 0; remainingTicks = 0
    }

    private fun execute() {
        while (!finished && dialogueId == null && videoId == null && remainingTicks == 0 && fadeTicks == 0) {
            val commands = data.get("programs")[program].get("commands")
            check(cursor < commands.size) { "시작 연출 종료 명령 누락" }
            val command = commands[cursor++]
            val value = command.get("value")
            when (command.getString("op")) {
                "S" -> {
                    actors.clear()
                    value.forEach { actors += Actor(it.getInt("sprite"), it.getFloat("x"), it.getFloat("y"), it.getInt("frame")) }
                }
                "M" -> {
                    check(value.size == actors.size)
                    value.forEachIndexed { i, row ->
                        actors[i].apply { sprite = row[0].asInt(); direction = row[1].asInt(); animate = row[2].asInt() == 1
                            frame = row[3].asInt(); speed = row[4].asInt(); mask = row[5].asInt() }
                    }
                }
                "L" -> { cameraDirection = value[0].asInt(); step = value[1].asInt(); check(step in 1..8 && 8 % step == 0) }
                "G" -> {
                    actors.filter { it.animate }.forEach { actor ->
                        actor.frame++
                        val base = if(actor.direction in 1..12) data.get("motion_frame_bases")[actor.direction-1].asInt() else -1
                        if (base >= 0 && actor.frame !in base..base+3) actor.frame = base
                    }
                    remainingTicks = 8 / step
                }
                "C" -> {
                    val id=value.asInt()
                    if(data.get("dialogues").get(id.toString()).size>0) {
                        dialogueId=id;pageIndex=0;visibleCharacters=0;textAccumulator=0f;check(page!=null)
                    }
                }
                "V" -> { videoId=value.asString();accumulator=0f }
                "F" -> Unit // 원작 효과음 번호는 데이터에 보존한다. 이번 단계는 화면·대화 실행을 연결한다.
                "E" -> { if(continuesField) finished=true else { fadeTicks=64;fadingOut=true } }
                else -> error("지원하지 않는 시작 연출 명령")
            }
        }
    }

    fun update(delta: Float) {
        require(delta.isFinite() && delta >= 0)
        if (finished || videoId!=null) return
        if (dialogueId != null) {
            textAccumulator += delta * 70f
            val count = textAccumulator.toInt(); textAccumulator -= count
            visibleCharacters = (visibleCharacters + count).coerceAtMost(page!!.getString("text").length)
            return
        }
        accumulator += delta
        while (accumulator >= 1f / 70f && !finished && dialogueId == null && videoId == null) {
            accumulator -= 1f / 70f
            if(fadeTicks>0) {
                fadeTicks--
                if(fadeTicks==0 && fadingOut) {
                    if(program<lastProgram) { program++;loadProgram();fadingOut=false;execute();fadeTicks=64 }
                    else finished=true
                }
                continue
            }
            if (remainingTicks > 0) {
                actors.forEach { actor ->
                    actor.x += dx(actor.direction) * actor.speed; actor.y += dy(actor.direction) * actor.speed
                }
                val m = data.get("maps").get(map.toString())
                scrollX = (scrollX + dx(cameraDirection) * step).coerceIn(0f, m.getInt("width")*8f-320f)
                scrollY = (scrollY + dy(cameraDirection) * step).coerceIn(0f, m.getInt("height")*8f-200f)
                totalTicks++; remainingTicks--
            }
            execute()
        }
        // 대화 대기 중에 쌓인 시간으로 다음 연출을 건너뛰지 않는다.
        if (dialogueId != null) accumulator = 0f
    }

    fun resumeVideo() {
        check(videoId!=null)
        videoId=null;accumulator=0f;execute()
    }

    fun advance() {
        if (dialogueId == null) return
        if (!pageComplete) { visibleCharacters = page!!.getString("text").length; return }
        val pages = data.get("dialogues").get(dialogueId.toString())
        if (pageIndex + 1 < pages.size) {
            val previous = page!!
            pageIndex++
            val retained = previous.getString("text").substringAfterLast('\n') + "\n"
            visibleCharacters = if(page!!.getString("speaker")==previous.getString("speaker") &&
                page!!.getString("text").startsWith(retained)) retained.length else 0
            textAccumulator = 0f
        }
        else { dialogueId = null; pageIndex = 0; accumulator = 0f; execute() }
    }

    companion object {
        fun parse(json: String) = OpeningTimeline(JsonReader().parse(json))
        fun dx(direction: Int) = when (direction) { 2,3,6 -> 1; 8,9,12 -> -1; else -> 0 }
        fun dy(direction: Int) = when (direction) { 1,3,9 -> -1; 4,6,12 -> 1; else -> 0 }
    }
}
