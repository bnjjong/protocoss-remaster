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
 * 바닥 레이어와 상위 오브젝트 레이어를 순차 렌더링하며, 화면 가시 영역 컬링(Frustum Culling)을
 * 적용하여 60fps 고정 성능을 보장합니다.
 *
 * @param tileMap 렌더링할 필드 맵 모델
 * @param tilesetTexture 16x16 타일들이 패킹된 RGBA 타일셋 시트 텍스처
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
     * [camera]의 가시 영역을 계산하여 현재 화면에 보이는 바닥 및 오브젝트 타일을 [batch]로 렌더링합니다.
     *
     * @param batch 2D 스프라이트 렌더러
     * @param camera 현재 뷰포트 직교 투영 카메라
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
                // 1단계: 바닥 레이어 렌더링 (0번 투명 타일 제외)
                val gId = tileMap.getGroundTile(tx, ty)
                if (gId > 0 && gId in tileRegions.indices) {
                    batch.draw(tileRegions[gId], tx * tw, ty * th, tw, th)
                }

                // 2단계: 상위 오브젝트/건물 레이어 렌더링
                val oId = tileMap.getOverlayTile(tx, ty)
                if (oId > 0 && oId in tileRegions.indices) {
                    batch.draw(tileRegions[oId], tx * tw, ty * th, tw, th)
                }
            }
        }
    }
}
