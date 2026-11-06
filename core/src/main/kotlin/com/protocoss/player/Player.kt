package com.protocoss.player

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch

/**
 * 필드 상의 주인공 '카만'의 위치, 8방향 이동 및 렌더링을 담당하는 엔티티 클래스입니다.
 *
 * 키보드 및 패드 입력을 받아 부드러운 8방향 픽셀 이동을 수행하고 현재 방향과 상태를 유지합니다.
 *
 * [initialX] 필드 초기 생성 X 좌표
 * [initialY] 필드 초기 생성 Y 좌표
 * [texture] 렌더링할 플레이어 스프라이트 텍스처
 */
class Player(
    initialX: Float,
    initialY: Float,
    private val texture: Texture? = null
) {
    /** 필드 상의 현재 X 좌표 (스프라이트 중심 기준) */
    var x: Float = initialX
        private set

    /** 필드 상의 현재 Y 좌표 (스프라이트 중심 기준) */
    var y: Float = initialY
        private set

    /** 현재 플레이어가 바라보고 있는 8방향 중 하나 */
    var direction: Direction = Direction.SOUTH
        private set

    /** 현재 플레이어의 행동 상태 (대기 또는 걷기) */
    var state: PlayerState = PlayerState.IDLE
        private set

    /** 플레이어 캐릭터 도트 가로 크기 (픽셀) */
    val width: Float = WIDTH

    /** 플레이어 캐릭터 도트 세로 크기 (픽셀) */
    val height: Float = HEIGHT

    /** 현재 프레임에서 플레이어가 이동 중인지 여부 */
    val isMoving: Boolean
        get() = state == PlayerState.WALKING

    /**
     * 매 프레임 입력 상태에 따라 플레이어의 위치와 방향을 갱신합니다.
     *
     * [delta] 이전 프레임으로부터 경과된 시간 (초)
     * [up] 위쪽 입력
     * [down] 아래쪽 입력
     * [left] 왼쪽 입력
     * [right] 오른쪽 입력
     * [isDash] 대시(고속 이동) 입력 여부
     */
    fun update(
        delta: Float,
        up: Boolean,
        down: Boolean,
        left: Boolean,
        right: Boolean,
        isDash: Boolean = false
    ) {
        val newDirection = Direction.fromInput(up, down, left, right)

        if (newDirection != null) {
            direction = newDirection
            state = PlayerState.WALKING

            val currentSpeed = if (isDash) DASH_SPEED else WALK_SPEED
            x += direction.dx * currentSpeed * delta
            y += direction.dy * currentSpeed * delta
        } else {
            state = PlayerState.IDLE
        }
    }

    /**
     * 지정된 [batch]를 사용하여 플레이어 스프라이트를 화면에 렌더링합니다.
     *
     * [batch] LibGDX 2D 스프라이트 렌더러
     */
    fun render(batch: SpriteBatch) {
        if (texture != null) {
            // 스프라이트 발밑 중심 정렬 렌더링
            batch.draw(
                texture,
                x - width / 2f,
                y - height / 2f,
                width,
                height
            )
        }
    }

    /**
     * 플레이어 위치를 강제로 [newX], [newY] 좌표로 이동합니다 (맵 워프/스폰 시 사용).
     */
    fun setPosition(newX: Float, newY: Float) {
        x = newX
        y = newY
    }

    companion object {
        /** 원작 카만 기본 가로 픽셀 크기 */
        const val WIDTH: Float = 34f

        /** 원작 카만 기본 세로 픽셀 크기 */
        const val HEIGHT: Float = 44f

        /** 일반 걷기 이동 속도 (초당 픽셀) */
        const val WALK_SPEED: Float = 85f

        /** 대시 달리기 이동 속도 (초당 픽셀) */
        const val DASH_SPEED: Float = 145f
    }
}
