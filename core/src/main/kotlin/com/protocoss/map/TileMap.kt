package com.protocoss.map

/**
 * 16x16 픽셀 타일 그리드로 구성된 2D 필드 맵 데이터 모델입니다.
 *
 * 필드 타일 인덱스 저장, 월드 좌표와 타일 좌표 간의 변환 및 지형 이동 가능 여부 판정을 위해 사용됩니다.
 *
 * [width] 가로 타일 개수
 * [height] 세로 타일 개수
 * [tileWidth] 단일 타일 가로 픽셀 크기 (기본 16)
 * [tileHeight] 단일 타일 세로 픽셀 크기 (기본 16)
 * [tiles] 1차원 배열로 펼쳐진 타일 ID 목록
 */
class TileMap(
    val width: Int,
    val height: Int,
    val tileWidth: Int = DEFAULT_TILE_SIZE,
    val tileHeight: Int = DEFAULT_TILE_SIZE,
    private val tiles: IntArray = IntArray(width * height)
) {
    /** 필드 전체 가로 픽셀 크기 */
    val totalPixelWidth: Float
        get() = (width * tileWidth).toFloat()

    /** 필드 전체 세로 픽셀 크기 */
    val totalPixelHeight: Float
        get() = (height * tileHeight).toFloat()

    /**
     * 지정된 타일 그리드 좌표 ([tileX], [tileY])의 타일 ID를 반환합니다.
     *
     * 범위를 벗어날 경우 0을 반환합니다.
     */
    fun getTile(tileX: Int, tileY: Int): Int {
        if (tileX !in 0 until width || tileY !in 0 until height) {
            return 0
        }
        return tiles[tileY * width + tileX]
    }

    /**
     * 지정된 타일 그리드 좌표 ([tileX], [tileY])에 타일 ID를 설정합니다.
     */
    fun setTile(tileX: Int, tileY: Int, tileId: Int) {
        if (tileX in 0 until width && tileY in 0 until height) {
            tiles[tileY * width + tileX] = tileId
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
    }
}
