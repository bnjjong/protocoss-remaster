package com.protocoss.menu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.Disposable
import com.protocoss.save.DosSave

/** 원작 6비트 팔레트의 정수 나눗셈으로 메뉴·휴식·필드 전체를 어둡게 한다. */
class TentRenderer:Disposable {
    private val menuBuffer=FrameBuffer(Pixmap.Format.RGBA8888,320,200,false)
    private val fieldBuffer=FrameBuffer(Pixmap.Format.RGBA8888,320,200,false)
    private val menu=region(menuBuffer)
    private val field=region(fieldBuffer)
    private val rest=TextureRegion(Texture(Gdx.files.internal("assets/tent/rest.png")).apply { setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest) })
    private val shader=ShaderProgram("""
        attribute vec4 a_position;
        attribute vec4 a_color;
        attribute vec2 a_texCoord0;
        uniform mat4 u_projTrans;
        varying vec2 v_texCoords;
        void main() { v_texCoords=a_texCoord0;gl_Position=u_projTrans*a_position; }
    """.trimIndent(),"""
        #ifdef GL_ES
        precision highp float;
        #endif
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;
        uniform float u_level;
        void main() {
            vec3 dac=floor(texture2D(u_texture,v_texCoords).rgb*255.0/4.0+0.001);
            gl_FragColor=vec4(floor(dac*u_level/64.0)*4.0/255.0,1.0);
        }
    """.trimIndent()).also { require(it.isCompiled) { it.log } }
    private fun region(buffer:FrameBuffer)=TextureRegion(buffer.colorBufferTexture.apply { setFilter(Texture.TextureFilter.Nearest,Texture.TextureFilter.Nearest) }).apply { flip(false,true) }
    fun captureMenu(batch:SpriteBatch,renderer:EquipmentRenderer,save:DosSave,selected:Int,scroll:Int) {
        menuBuffer.begin();Gdx.gl.glClearColor(0f,0f,0f,1f);Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        renderer.inventory(batch,save,false,selected,scroll);menuBuffer.end()
    }
    fun beginField()=fieldBuffer.begin()
    fun endField()=fieldBuffer.end()
    fun render(batch:SpriteBatch,state:TentRest) {
        val source=when(state.phase) { TentRest.Phase.MENU_OUT->menu;TentRest.Phase.RETURN_IN->field;else->rest }
        val previous=batch.shader
        batch.shader=shader;batch.disableBlending();batch.begin();shader.setUniformf("u_level",state.level.toFloat())
        batch.draw(source,0f,0f,320f,200f);batch.end();batch.enableBlending();batch.shader=previous
    }
    override fun dispose() { menuBuffer.dispose();fieldBuffer.dispose();rest.texture.dispose();shader.dispose() }
}
