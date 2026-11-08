package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue
import kotlin.math.floor

/** 원작 54바이트 인물 레코드와 0x23DA4의 이동. 좌표는 그림 왼쪽 위다. */
class FieldNpcs(source: JsonValue, private val clock: Clock = Clock()) {
    class Actor(value: JsonValue) {
        val sprite=value.getInt("sprite")
        var x=value.getInt("x"); internal set
        var y=value.getInt("y"); internal set
        var tileX=value.getInt("tile_x"); internal set
        var tileY=value.getInt("tile_y"); internal set
        val bounds=value.get("bounds").asIntArray()
        val event=value.getInt("event")
        var direction=value.getInt("direction"); internal set
        var frame=value.getInt("frame"); internal set
        val kind=value.getInt("kind")
        val speed=value.getInt("speed")
        var mode=value.getInt("mode"); internal set
        val mask=value.getInt("mask")
    }
    /** BIOS 시계와 난수는 맵을 바꿔도 이어진다. 메뉴에서 인물 갱신은 멈춘다. */
    class Clock(var seed: Int = 1) {
        private var phase=0.0
        var biosTicks=0; private set
        internal var frameDue=false
        internal var randomDue=false
        internal fun tick() {
            phase+=1193182.0/(65536.0*70.0)
            while(phase>=1.0) {
                phase-=1.0;biosTicks++
                if(biosTicks%4==0) frameDue=true
                if(biosTicks%60==0) randomDue=true
            }
        }
        internal fun randomDirection(): Int {
            seed=seed*0x41c64e6d+0x3039
            return 1 shl ((seed ushr 16) and 3)
        }
    }
    val actors=source.get("npcs").map { Actor(it) }
    private val width=source.getInt("width")
    private val height=source.getInt("height")
    private val cells=source.get("cells").map { it.asIntArray() }
    private var talking: Int?=null
    private var savedDirection=0
    private var savedFrame=0
    private var talkPose=0
    val talkReady get()=talking?.let { actors[it].mode==2 } ?: false

    fun changeCells(changes:JsonValue) {
        changes.forEach { cells[it.getInt("index")].apply { this[0]=it.getInt("tid");this[1]=it.getInt("attr") } }
    }

    private fun blocked(x:Int,y:Int,layer:Int): Boolean {
        if(x !in 0 until width || y !in 0 until height) return true
        val cell=cells[y*width+x]
        return when(layer) { 0->cell[0] and 0x1000!=0;1->cell[1] and 0x20!=0;else->cell[1] and 0x100!=0 }
    }

    /** 원작 0x1FD84는 현재 그림이 아닌 목표 격자 좌표로 플레이어를 막는다. */
    fun blocksPlayer(x:Float,y:Float,direction:Int): Boolean {
        val px=floor(x/8).toInt();val py=floor(y/8).toInt()
        return actors.any { a -> when(direction) {
            1 -> a.tileX in px-2..px+2 && a.tileY==py-1
            2 -> a.tileX==px+3 && a.tileY==py
            4 -> a.tileX in px-2..px+1 && a.tileY==py+1
            8 -> a.tileX==px-3 && a.tileY==py
            else -> false
        } }
    }

    private fun blocked(a:Actor,px:Int,py:Int):Boolean {
        val x=a.tileX;val y=a.tileY
        val terrain=when(a.direction) {
            1 -> (x..x+2).any { blocked(it,y+1,a.mask) }
            2 -> blocked(x+3,y+2,a.mask)
            4 -> (x..x+2).any { blocked(it,y+3,a.mask) }
            else -> blocked(x-1,y+2,a.mask)
        }
        val hero=when(a.direction) {
            1 -> px in x-2..x+2 && py==y-1
            2 -> px==x+3 && py==y
            4 -> px in x-2..x+2 && py==y+1
            else -> px==x-3 && py==y
        }
        return terrain || hero
    }

    /** 0x21464의 정면 사각형·높이와 0x21250의 지형 가림 검사를 적용한다. */
    fun talkTarget(x:Float,y:Float,pose:Int,layer:Int):Int? {
        val px=floor(x/8).toInt();val py=floor(y/8).toInt()
        val area=when(pose/4) {
            0 -> intArrayOf(px-2,py-4,px+2,py-1)
            1 -> intArrayOf(px+3,py-2,px+6,py+2)
            2 -> intArrayOf(px-2,py+1,px+2,py+4)
            else -> intArrayOf(px-6,py-2,px-3,py+2)
        }
        val index=actors.indexOfFirst { it.tileX in area[0]..area[2] && it.tileY in area[1]..area[3] && it.mask==layer }
        if(index<0) return null
        val a=actors[index]
        if(a.kind>1) {
            val line=when(pose/4) {
                0 -> List((py-a.tileY).coerceAtLeast(0)) { px+1 to py+1-it }
                1 -> List((a.tileX-px-2).coerceAtLeast(0)) { px+3+it to py+2 }
                2 -> List((a.tileY-py).coerceAtLeast(0)) { px+1 to py+3+it }
                else -> List((px-a.tileX-2).coerceAtLeast(0)) { px-1-it to py+2 }
            }
            if(line.any { blocked(it.first,it.second,layer) }) return null
        }
        return index.takeIf { a.event!=-1 }
    }

    fun beginTalk(index:Int,pose:Int) {
        check(talking==null)
        val a=actors[index];talking=index;talkPose=pose/4*4
        savedDirection=a.direction;savedFrame=a.frame;a.mode=1;clock.frameDue=true
    }
    fun endTalk() {
        val a=actors[requireNotNull(talking)]
        a.mode=0;a.direction=savedDirection;a.frame=savedFrame
        a.tileX=a.x shr 3;a.tileY=a.y shr 3;talking=null
    }
    private fun approach(a:Actor,px:Int,py:Int) {
        val tx=px+when(talkPose) { 4->24;12->-24;else->0 }
        val ty=py+when(talkPose) { 0->-8;8->8;else->0 }
        val horizontalFirst=talkPose==0 || talkPose==8
        fun horizontal():Boolean {
            if(a.x==tx) return false
            a.direction=if(a.x<tx) 2 else 8;a.x+=if(a.x<tx) 1 else -1;return true
        }
        fun vertical():Boolean {
            if(a.y==ty) return false
            a.direction=if(a.y<ty) 4 else 1;a.y+=if(a.y<ty) 1 else -1;return true
        }
        if(a.kind>1 && (if(horizontalFirst) horizontal() || vertical() else vertical() || horizontal())) return
        a.frame=when(talkPose) { 0->8;4->12;8->0;else->4 };a.mode=2
    }

    fun tick(playerX:Float,playerY:Float) {
        clock.tick()
        talking?.let { if(!talkReady) approach(actors[it],playerX.toInt(),playerY.toInt()) }
        if(clock.randomDue) {
            actors.filter { it.kind==2 && it.mode==0 }.forEach { it.direction=clock.randomDirection() }
            clock.randomDue=false;clock.frameDue=false
        }
        if(clock.frameDue) {
            actors.forEach { a ->
                if(a.kind!=0 && a.mode!=2) {
                    val base=when(a.direction) { 1->0;2->4;4->8;else->12 }
                    a.frame=(a.frame+1).takeIf { it in base..base+3 } ?: base
                }
                if(a.kind==2 && a.mode==0) {
                    val edge=when(a.direction) {
                        1 -> a.tileY<=a.bounds[1];2 -> a.tileX>=a.bounds[2]
                        4 -> a.tileY>=a.bounds[3];else -> a.tileX<=a.bounds[0]
                    }
                    if(edge || blocked(a,floor(playerX/8).toInt(),floor(playerY/8).toInt())) {
                        a.direction=when(a.direction) { 1->2;2->4;4->8;else->1 }
                        a.frame=when(a.direction) { 1->0;2->4;4->8;else->12 }
                    } else { a.tileX+=FieldRules.dx(a.direction);a.tileY+=FieldRules.dy(a.direction) }
                }
            }
            clock.frameDue=false
        }
        actors.filter { it.mode==0 }.forEach { a ->
            a.x+=a.speed*(a.tileX*8-a.x).compareTo(0)
            a.y+=a.speed*(a.tileY*8-a.y).compareTo(0)
        }
    }
}
