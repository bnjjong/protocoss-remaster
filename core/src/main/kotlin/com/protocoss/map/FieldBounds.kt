package com.protocoss.map

import com.badlogic.gdx.graphics.OrthographicCamera
import com.protocoss.player.Player

/** 맵 크기에 맞춰 캐릭터와 카메라가 머물 수 있는 범위를 계산한다. */
object FieldBounds {
    /** 현재 그리는 스프라이트 전체가 맵 안에 들어오도록 위치를 제한한다. */
    fun constrainPlayer(player: Player, map: TileMap) {
        player.setPosition(
            constrainCenter(player.x, player.width, map.totalPixelWidth),
            constrainCenter(player.y, player.height, map.totalPixelHeight)
        )
    }

    /** 카메라의 확대 배율을 반영한다. 화면보다 작은 축은 맵 중앙에 맞춘다. */
    fun constrainCamera(camera: OrthographicCamera, map: TileMap) {
        camera.position.x = constrainCenter(
            camera.position.x, camera.viewportWidth * camera.zoom, map.totalPixelWidth
        )
        camera.position.y = constrainCenter(
            camera.position.y, camera.viewportHeight * camera.zoom, map.totalPixelHeight
        )
    }

    private fun constrainCenter(center: Float, visibleSize: Float, mapSize: Float): Float {
        if (visibleSize >= mapSize) return mapSize * 0.5f
        val halfSize = visibleSize * 0.5f
        return center.coerceIn(halfSize, mapSize - halfSize)
    }
}
