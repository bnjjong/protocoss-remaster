package com.protocoss.map

import com.badlogic.gdx.graphics.OrthographicCamera
import com.protocoss.player.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldBoundsTest {
    private fun camera(x: Float, y: Float, zoom: Float = 1f) = OrthographicCamera().apply {
        viewportWidth = 320f
        viewportHeight = 200f
        this.zoom = zoom
        position.set(x, y, 0f)
    }

    @Test
    fun `screen sized cabin always keeps camera at map center`() {
        val map = TileMap(40, 25, 8, 8)
        for ((x, y) in listOf(-500f to -300f, 160f to 100f, 900f to 800f)) {
            val camera = camera(x, y)
            FieldBounds.constrainCamera(camera, map)
            assertEquals(160f, camera.position.x)
            assertEquals(100f, camera.position.y)
        }
    }

    @Test
    fun `larger map permits interior scrolling and stops at visible edges`() {
        val map = TileMap(80, 50, 8, 8)
        val camera = camera(280f, 175f)
        FieldBounds.constrainCamera(camera, map)
        assertEquals(280f, camera.position.x)
        assertEquals(175f, camera.position.y)

        camera.position.set(-100f, 900f, 0f)
        FieldBounds.constrainCamera(camera, map)
        assertEquals(160f, camera.position.x)
        assertEquals(300f, camera.position.y)

        camera.position.set(1000f, -100f, 0f)
        FieldBounds.constrainCamera(camera, map)
        assertEquals(480f, camera.position.x)
        assertEquals(100f, camera.position.y)
    }

    @Test
    fun `camera zoom affects the visible world size`() {
        val map = TileMap(80, 50, 8, 8)
        val camera = camera(0f, 0f, 0.5f)
        FieldBounds.constrainCamera(camera, map)
        assertEquals(80f, camera.position.x)
        assertEquals(50f, camera.position.y)

        camera.zoom = 2f
        FieldBounds.constrainCamera(camera, map)
        assertEquals(320f, camera.position.x)
        assertEquals(200f, camera.position.y)

        camera.zoom = 3f
        FieldBounds.constrainCamera(camera, map)
        assertEquals(320f, camera.position.x)
        assertEquals(200f, camera.position.y)
    }

    @Test
    fun `map smaller than view on one axis centers only that axis`() {
        val map = TileMap(80, 10, 8, 8)
        val camera = camera(600f, -90f)
        FieldBounds.constrainCamera(camera, map)
        assertEquals(480f, camera.position.x)
        assertEquals(40f, camera.position.y)
    }

    @Test
    fun `walk and dash cannot move the whole sprite outside the cabin`() {
        val map = TileMap(40, 25, 8, 8)
        val directions = listOf(
            listOf(true, false, false, false), listOf(false, true, false, false),
            listOf(false, false, true, false), listOf(false, false, false, true),
            listOf(true, false, true, false), listOf(true, false, false, true),
            listOf(false, true, true, false), listOf(false, true, false, true)
        )
        for (dash in listOf(false, true)) {
            for (keys in directions) {
                val player = Player(160f, 100f)
                player.update(100f, keys[0], keys[1], keys[2], keys[3], dash)
                FieldBounds.constrainPlayer(player, map)
                assertTrue(player.x - player.width / 2 >= 0f)
                assertTrue(player.x + player.width / 2 <= 320f)
                assertTrue(player.y - player.height / 2 >= 0f)
                assertTrue(player.y + player.height / 2 <= 200f)
            }
        }
    }

    @Test
    fun `out of map player is recovered while interior position is unchanged`() {
        val map = TileMap(40, 25, 8, 8)
        val player = Player(-800f, 900f)
        FieldBounds.constrainPlayer(player, map)
        assertEquals(17f, player.x)
        assertEquals(178f, player.y)
        player.setPosition(303f, 22f)
        FieldBounds.constrainPlayer(player, map)
        assertEquals(303f, player.x)
        assertEquals(22f, player.y)
        player.setPosition(160f, 100f)
        FieldBounds.constrainPlayer(player, map)
        assertEquals(160f, player.x)
        assertEquals(100f, player.y)
    }

    @Test
    fun `tiny map centers sprite and camera without invalid clamp range`() {
        val map = TileMap(1, 1, 8, 8)
        val player = Player(-800f, 900f)
        val camera = camera(900f, -800f)
        FieldBounds.constrainPlayer(player, map)
        FieldBounds.constrainCamera(camera, map)
        assertEquals(4f, player.x)
        assertEquals(4f, player.y)
        assertEquals(4f, camera.position.x)
        assertEquals(4f, camera.position.y)
    }
}
