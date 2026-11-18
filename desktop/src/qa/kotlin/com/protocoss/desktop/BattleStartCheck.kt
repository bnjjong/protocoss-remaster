package com.protocoss.desktop

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.protocoss.battle.*
import com.protocoss.save.DosSave
import java.util.Base64

/** 사용자 저장·원본·오디오 없이 파티 배치와 명령 안내를 실제 그래픽 출력으로 대조한다. */
object BattleStartCheck {
    @JvmStatic fun main(args:Array<String>) {
        val app=BattleStartApplication()
        Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
            setTitle("전투 시작 파티 검사");setWindowedMode(640,400);setInitialVisible(false)
            useVsync(false);setForegroundFPS(0);disableAudio(true)
        })
        check(app.completed)
    }
}

private class BattleStartApplication:ApplicationAdapter() {
    var completed=false;private set
    override fun create() {
        val entry=BattleEntry(Gdx.files.internal("assets/battle/first-battle.json").readString("UTF-8"))
        val party=BattleParty(Gdx.files.internal("assets/battle/start.json").readString("UTF-8"))
        val enemies=BattleFormationRenderer();val actors=BattlePartyRenderer(party)
        val batch=SpriteBatch();val frame=FrameBuffer(Pixmap.Format.RGBA8888,320,200,false)
        try {
            batch.projectionMatrix=Matrix4().setToOrtho2D(0f,0f,320f,200f)
            val scenes=party.data.get("scenes")
            for(scene in scenes) {
                val save=DosSave.parse(Base64.getDecoder().decode(scene.getString("before")))
                frame.begin()
                try {
                    Gdx.gl.glClearColor(0f,0f,0f,1f);Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                    batch.begin();enemies.draw(batch,entry.formations.getValue(50))
                    actors.draw(batch,party.prepare(save),scene.getBoolean("guide"));batch.end()
                    val actual=Pixmap.createFromFrameBuffer(0,0,320,200)
                    val expected=Pixmap(Gdx.files.internal("assets/battle/${scene.getString("file")}"))
                    try {
                        for(y in 0 until 200)for(x in 0 until 320)
                            check(actual.getPixel(x,199-y)==expected.getPixel(x,y)) { "${scene.getString("file")} ($x,$y) 불일치" }
                        PixmapIO.writePNG(Gdx.files.local("desktop/build/reports/battle-start/${scene.getString("file")}"),actual,-1,true)
                    } finally { actual.dispose();expected.dispose() }
                } finally { frame.end() }
            }
            println("전투 시작 파티·명령 안내 ${scenes.size}장·${scenes.size*64000}픽셀 원작/GPU 일치. 일반 플레이 전투는 아직 미연결.")
            completed=true
        } finally { frame.dispose();batch.dispose();actors.dispose();enemies.dispose();Gdx.app.exit() }
    }
}
