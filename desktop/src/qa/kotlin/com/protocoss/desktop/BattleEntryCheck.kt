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
import com.protocoss.battle.BattleEntry
import com.protocoss.battle.BattleFormationRenderer

/** 원본 파일·음악·사용자 저장 없이 네 적 편성을 GPU로 그리고 원작 기준 PNG와 비교한다. */
object BattleEntryCheck {
    @JvmStatic fun main(args: Array<String>) {
        val app = BattleEntryApplication()
        Lwjgl3Application(app,Lwjgl3ApplicationConfiguration().apply {
            setTitle("북마겔 적 편성 검사");setWindowedMode(640,400)
            setInitialVisible(false);useVsync(false);setForegroundFPS(0)
            disableAudio(true)
        })
        check(app.completed)
    }
}

private class BattleEntryApplication : ApplicationAdapter() {
    var completed = false;private set
    override fun create() {
        val entry=BattleEntry(Gdx.files.internal("assets/battle/first-battle.json").readString("UTF-8"))
        val renderer=BattleFormationRenderer();val batch=SpriteBatch()
        val frame=FrameBuffer(Pixmap.Format.RGBA8888,320,200,false)
        try {
            batch.projectionMatrix=Matrix4().setToOrtho2D(0f,0f,320f,200f)
            for (id in entry.formations.keys) {
                val field=checkNotNull(entry.freshField(0,13))
                val index=field.snapshot().indexOf(id)
                check(index>=0)
                val formation=checkNotNull(field.poll((index%entry.width-1)*8f,(index/entry.width-2)*8f,false,true,false))
                check(formation.id==id)
                check(field.poll((index%entry.width-1)*8f,(index/entry.width-2)*8f,false,true,false)==null)
                frame.begin()
                try {
                    Gdx.gl.glClearColor(0f,0f,0f,1f);Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                    batch.begin();renderer.draw(batch,formation);batch.end()
                    val actual=Pixmap.createFromFrameBuffer(0,0,320,200)
                    val expected=Pixmap(Gdx.files.internal("assets/battle/formation-${formation.id}.png"))
                    try {
                        for (y in 0 until 200) for (x in 0 until 320)
                            check(actual.getPixel(x,199-y)==expected.getPixel(x,y)) { "편성 ${formation.id} 픽셀 ($x,$y) 불일치" }
                        PixmapIO.writePNG(Gdx.files.local("desktop/build/reports/battle-entry/formation-${formation.id}.png"),actual,-1,true)
                    } finally { actual.dispose();expected.dispose() }
                } finally { frame.end() }
            }
            println("북마겔 적 편성 4장·256,000픽셀 GPU/원작 일치. 일반 플레이 전투 연결은 후속 단계.")
            completed=true
        } finally { frame.dispose();batch.dispose();renderer.dispose();Gdx.app.exit() }
    }
}
