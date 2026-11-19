package com.protocoss.desktop

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.JsonReader
import com.protocoss.battle.*
import com.protocoss.save.DosSave
import java.security.MessageDigest
import java.util.Base64

/** 공격 시각 요소를 원본·오디오·사용자 저장 없이 실제 그래픽 출력과 대조한다. */
object BattleAttackCheck {
    @JvmStatic fun main(args:Array<String>) {
        val app=AttackCheckApplication()
        Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
            setTitle("기본 공격 검사");setWindowedMode(640,400);setInitialVisible(false)
            useVsync(false);setForegroundFPS(0);disableAudio(true)
        })
        check(app.completed)
    }
}

private class AttackCheckApplication:ApplicationAdapter() {
    var completed=false;private set
    override fun create() {
        val meta=Gdx.files.internal("assets/battle/attack/attack.json").readString("UTF-8")
        val report=JsonReader().parse(Gdx.files.local("docs/restoration/battle-attack-native.json").readString("UTF-8"))
        val party=BattleParty(Gdx.files.internal("assets/battle/start.json").readString("UTF-8"))
        val entry=BattleEntry(Gdx.files.internal("assets/battle/first-battle.json").readString("UTF-8"))
        val renderer=AttackRenderer(meta);val batch=SpriteBatch();val frame=FrameBuffer(Pixmap.Format.RGBA8888,320,200,false)
        var count=0
        try {
            batch.projectionMatrix=Matrix4().setToOrtho2D(0f,0f,320f,200f)
            for(row in report.get("cases")) {
                val actors=party.prepare(DosSave.parse(Base64.getDecoder().decode(row.getString("before"))))
                val formation=entry.formations.getValue(row.getInt("group"))
                val conditions=row.get("conditions")?.map { it.asInt() }
                val target=AttackTarget(formation.enemies.mapIndexed { i,e->AttackTarget.Enemy(i,e.x,e.y,conditions?.get(i) ?: 1,0,0) }).current.slot
                val attack=BasicAttack(meta,actors,formation,row.getInt("party"),target,DosBattleRandom({ row.getInt("clock") },row.getLong("counter")),row.get("health")?.map { it.asInt() },conditions,row.getInt("previous",0))
                for(expected in row.get("frames").filter { it.getString("phase")!="target" }) {
                    val state=checkNotNull(attack.next())
                    frame.begin()
                    try {
                        Gdx.gl.glClearColor(0f,0f,0f,1f);Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                        batch.begin();renderer.draw(batch,attack,formation,state);batch.end()
                        val image=Pixmap.createFromFrameBuffer(0,0,320,200)
                        try {
                            val bytes=ByteArray(320*200*4)
                            for(y in 0 until 200)for(x in 0 until 320) {
                                val color=image.getPixel(x,199-y);val i=(y*320+x)*4
                                bytes[i]=(color ushr 24).toByte();bytes[i+1]=(color ushr 16).toByte();bytes[i+2]=(color ushr 8).toByte();bytes[i+3]=color.toByte()
                            }
                            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                            check(hash==expected.getString("rgba_sha256")) { "공격 화면 $count (${state.phase}) 불일치" }
                        } finally { image.dispose() }
                    } finally { frame.end() }
                    count++
                }
                check(attack.next()==null)
            }
            println("기본 공격 ${report.get("cases").size}개·$count 시각 상태·${count*64000}픽셀 GPU 비교 통과. 일반 플레이 전투는 아직 미연결.")
            completed=true
        } finally { frame.dispose();batch.dispose();renderer.dispose();Gdx.app.exit() }
    }
}
