package com.protocoss.map

import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import kotlin.math.max
import kotlin.math.min

/**
 * [TileMap]의 타일 그리드를 카메라 영역에 맞춰 효율적으로 렌더링하는 컴포넌트입니다.
 *
 * 뷰포트 외부의 타일을 자동으로 제외하는 화면 컬링(Frustum Culling)을 적용하여 60fps 고정 성능을 유지합니다.
 *
 * [tileMap] 렌더링할 필드 맵 모델
 * [tilesetTexture] 16x16 타일들이 패킹된 타일셋 시트 텍스처
 */
class TileMapRenderer(
    private val tileMap: TileMap,
    private val tilesetTexture: Texture
) {
    private val tileRegions: Array<TextureRegion>

    init {
        val cols = tilesetTexture.width / tileMap.tileWidth
        val rows = tilesetTexture.height / tileMap.tileHeight
        val totalTiles = cols * rows

        tileRegions = Array(totalTiles) { idx ->
            val col = idx % cols
            val row = idx / cols
            TextureRegion(
                tilesetTexture,
                col * tileMap.tileWidth,
                row * tileMap.tileHeight,
                tileMap.tileWidth,
                tileMap.tileHeight
            )
        }
    }

    /**
     * [camera]의 가시 영역을 계산하여 현재 화면에 보이는 타일만 [batch]로 렌더링합니다.
     *
     * [batch] 2D 스프라이트 렌더러
     * [camera] 현재 뷰포트 직교 투영 카메라
     */
    fun render(batch: SpriteBatch, camera: OrthographicCamera) {
        val halfW = camera.viewportWidth * 0.5f * camera.zoom
        val halfH = camera.viewportHeight * 0.5f * camera.zoom

        val startX = max(0, tileMap.worldToTileX(camera.position.x - halfW) - 1)
        val endX = min(tileMap.width - 1, tileMap.worldToTileX(camera.position.x + halfW) + 1)
        val startY = max(0, tileMap.worldToTileY(camera.position.y - halfH) - 1)
        val endY = min(tileMap.height - 1, tileMap.worldToTileY(camera.position.y + halfH) + 1)

        val tw = tileMap.tileWidth.toFloat()
        val th = tileMap.tileHeight.toFloat()

        for (ty in startY..endY) {
            for (tx in startX..endX) {
                val tileId = tileMap.getTile(tx, ty)
                if (tileId in tileRegions.indices) {
                    val region = tileRegions[tileId]
                    batch.draw(region, tx * tw, ty * th, tw, th)
                }
            }
        }
    }
}
