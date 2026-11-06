package com.protocoss.player

private const val INV_SQRT_2: Float = 0.70710677f

/**
 * 2D 필드 상에서 플레이어 및 NPC가 바라보고 이동할 수 있는 8개 방향을 정의합니다.
 *
 * 1995년 원작의 대각선 8방향 이동 및 방향별 스프라이트 시트 매핑을 위해 사용됩니다.
 *
 * @property dx X축 정규화 이동 단위 벡터
 * @property dy Y축 정규화 이동 단위 벡터
 */
enum class Direction(val dx: Float, val dy: Float) {
    SOUTH(0f, -1f),
    SOUTH_WEST(-INV_SQRT_2, -INV_SQRT_2),
    WEST(-1f, 0f),
    NORTH_WEST(-INV_SQRT_2, INV_SQRT_2),
    NORTH(0f, 1f),
    NORTH_EAST(INV_SQRT_2, INV_SQRT_2),
    EAST(1f, 0f),
    SOUTH_EAST(INV_SQRT_2, -INV_SQRT_2);

    companion object {
        /**
         * 상하좌우 4방향 키 입력 상태로부터 해당하는 [Direction]을 계산합니다.
         *
         * [up] 위쪽 방향 입력 여부
         * [down] 아래쪽 방향 입력 여부
         * [left] 왼쪽 방향 입력 여부
         * [right] 오른쪽 방향 입력 여부
         * 반대되는 키 입력이 동시에 들어올 경우 상쇄되며, 입력이 없을 시 null을 반환합니다.
         */
        fun fromInput(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Direction? {
            val y = (if (up) 1 else 0) - (if (down) 1 else 0)
            val x = (if (right) 1 else 0) - (if (left) 1 else 0)

            return when {
                x == 0 && y > 0 -> NORTH
                x == 0 && y < 0 -> SOUTH
                x > 0 && y == 0 -> EAST
                x < 0 && y == 0 -> WEST
                x > 0 && y > 0 -> NORTH_EAST
                x > 0 && y < 0 -> SOUTH_EAST
                x < 0 && y > 0 -> NORTH_WEST
                x < 0 && y < 0 -> SOUTH_WEST
                else -> null
            }
        }
    }
}
