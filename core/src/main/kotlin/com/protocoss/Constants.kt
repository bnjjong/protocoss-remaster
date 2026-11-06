package com.protocoss

/**
 * 프로토코스 리마스터 엔진의 해상도 및 기본 시스템 설정 상수를 정의합니다.
 *
 * 1995년 DOS 원작의 320x200 픽셀 비율을 유지하며 고해상도 모니터에서 정수배 스케일링을 지원하기 위해 사용됩니다.
 */
object Constants {
    /** 1995년 원작 기본 가로 해상도 (픽셀) */
    const val ORIGINAL_WIDTH: Int = 320

    /** 1995년 원작 기본 세로 해상도 (픽셀) */
    const val ORIGINAL_HEIGHT: Int = 200

    /** 데스크톱 기본 윈도우 스케일 배율 (4배: 1280x800) */
    const val DEFAULT_WINDOW_SCALE: Int = 4

    /** 데스크톱 창 초기 가로 크기 */
    const val WINDOW_WIDTH: Int = ORIGINAL_WIDTH * DEFAULT_WINDOW_SCALE

    /** 데스크톱 창 초기 세로 크기 */
    const val WINDOW_HEIGHT: Int = ORIGINAL_HEIGHT * DEFAULT_WINDOW_SCALE

    /** 게임 윈도우 타이틀 */
    const val GAME_TITLE: String = "프로토코스: 신들의 예언서 (Protocoss Remaster)"
}
