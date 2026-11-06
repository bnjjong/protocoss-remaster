package com.protocoss.map

import com.badlogic.gdx.utils.JsonReader

/**
 * 16x16 픽셀 타일 그리드로 구성된 2D 필드 맵 데이터 모델입니다.
 *
 * 바닥 레이어(Ground Layer)와 상위 오브젝트 레이어(Overlay Layer)의 2중 타일 구조를 지원합니다.
 *
 * @property width 가로 타일 개수
 * @property height 세로 타일 개수
 * @property tileWidth 단일 타일 가로 픽셀 크기 (기본 16)
 * @property tileHeight 단일 타일 세로 픽셀 크기 (기본 16)
 */
class TileMap(
    val width: Int,
    val height: Int,
    val tileWidth: Int = DEFAULT_TILE_SIZE,
    val tileHeight: Int = DEFAULT_TILE_SIZE,
    private val groundTiles: IntArray = IntArray(width * height),
    private val overlayTiles: IntArray = IntArray(width * height)
) {
    /** 필드 전체 가로 픽셀 크기 */
    val totalPixelWidth: Float
        get() = (width * tileWidth).toFloat()

    /** 필드 전체 세로 픽셀 크기 */
    val totalPixelHeight: Float
        get() = (height * tileHeight).toFloat()

    /**
     * 바닥 레이어의 지정 타일 그리드 좌표 ([tileX], [tileY])의 타일 ID를 반환합니다.
     */
    fun getTile(tileX: Int, tileY: Int): Int = getGroundTile(tileX, tileY)

    /**
     * 바닥 레이어의 지정 타일 그리드 좌표에 타일 ID를 설정합니다.
     */
    fun setTile(tileX: Int, tileY: Int, tileId: Int) = setGroundTile(tileX, tileY, tileId)

    /**
     * 바닥 레이어의 지정 타일 ID를 반환합니다.
     */
    fun getGroundTile(tileX: Int, tileY: Int): Int {
        if (tileX !in 0 until width || tileY !in 0 until height) return 0
        return groundTiles[tileY * width + tileX]
    }

    /**
     * 상위 오브젝트 레이어의 지정 타일 ID를 반환합니다.
     */
    fun getOverlayTile(tileX: Int, tileY: Int): Int {
        if (tileX !in 0 until width || tileY !in 0 until height) return 0
        return overlayTiles[tileY * width + tileX]
    }

    /**
     * 바닥 레이어에 타일 ID를 설정합니다.
     */
    fun setGroundTile(tileX: Int, tileY: Int, tileId: Int) {
        if (tileX in 0 until width && tileY in 0 until height) {
            groundTiles[tileY * width + tileX] = tileId
        }
    }

    /**
     * 상위 오브젝트 레이어에 타일 ID를 설정합니다.
     */
    fun setOverlayTile(tileX: Int, tileY: Int, tileId: Int) {
        if (tileX in 0 until width && tileY in 0 until height) {
            overlayTiles[tileY * width + tileX] = tileId
        }
    }

    /**
     * 월드 픽셀 좌표 [worldX]를 타일 그리드 X 좌표로 변환합니다.
     */
    fun worldToTileX(worldX: Float): Int = (worldX / tileWidth).toInt()

    /**
     * 월드 픽셀 좌표 [worldY]를 타일 그리드 Y 좌표로 변환합니다.
     */
    fun worldToTileY(worldY: Float): Int = (worldY / tileHeight).toInt()

    /**
     * 지정된 월드 좌표 ([worldX], [worldY])가 맵 내부인지 검증합니다.
     */
    fun isInBounds(worldX: Float, worldY: Float): Boolean {
        return worldX in 0f..totalPixelWidth && worldY in 0f..totalPixelHeight
    }

    companion object {
        /** 원작 기본 타일 단위 크기 (16x16 픽셀) */
        const val DEFAULT_TILE_SIZE: Int = 16

        /**
         * 추출된 맵 JSON 문자열로부터 [TileMap] 인스턴스를 역직렬화하여 생성합니다.
         */
        fun fromJson(jsonContent: String): TileMap {
            val root = JsonReader().parse(jsonContent)
            val w = root.getInt("width", 60)
            val h = root.getInt("height", 40)
            val tw = root.getInt("tile_width", DEFAULT_TILE_SIZE)
            val th = root.getInt("tile_height", DEFAULT_TILE_SIZE)

            val rawGround = IntArray(w * h)
            val rawOverlay = IntArray(w * h)

            val l1Node = root.get("layer1")
            val l2Node = root.get("layer2")

            if (l1Node != null) {
                var c = l1Node.child
                var idx = 0
                while (c != null && idx < rawGround.size) {
                    rawGround[idx] = c.asInt()
                    c = c.next
                    idx++
                }
            }

            if (l2Node != null) {
                var c = l2Node.child
                var idx = 0
                while (c != null && idx < rawOverlay.size) {
                    rawOverlay[idx] = c.asInt()
                    c = c.next
                    idx++
                }
            }

            // 도스 맵(Top-Down Y) 데이터를 LibGDX 표준 월드(Bottom-Up Y) 좌표계로 상하 반전 정렬
            val ground = IntArray(w * h)
            val overlay = IntArray(w * h)
            for (ty in 0 until h) {
                val srcRow = (h - 1 - ty) * w
                val dstRow = ty * w
                System.arraycopy(rawGround, srcRow, ground, dstRow, w)
                System.arraycopy(rawOverlay, srcRow, overlay, dstRow, w)
            }

            return TileMap(w, h, tw, th, ground, overlay)
        }
    }
}
