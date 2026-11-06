package com.protocoss.player

/**
 * 플레이어 캐릭터의 행동 상태를 정의합니다.
 *
 * 정지(Idle) 상태와 걷기(Walking) 모션 사이의 애니메이션 프레임 전환을 제어하기 위해 사용됩니다.
 */
enum class PlayerState {
    /** 제자리에 멈춰 서 있는 대기 상태 */
    IDLE,

    /** 필드를 이동 중인 걷기 상태 */
    WALKING
}
