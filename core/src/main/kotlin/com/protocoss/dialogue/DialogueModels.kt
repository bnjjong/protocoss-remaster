package com.protocoss.dialogue

/**
 * 대화창 한 화면에 표시되는 개별 대사 페이지 단위입니다.
 *
 * 한 번에 2~3줄 분량의 텍스트와 화자 정보를 담으며, 사용자가 넘김 키(Space/Enter)를 누를 때마다
 * 다음 페이지로 전환됩니다.
 *
 * @property speakerName 정제된 화자 이름 (예: "카만", "벨슨")
 * @property text 표시할 대사 텍스트 본문
 * @property portraitId 화자 초상화 식별 번호 (태그에서 추출)
 */
data class DialoguePage(
    val speakerName: String,
    val text: String,
    val portraitId: Int = 0
)

/**
 * 원작 스크립트의 화자 태그별 대사 블록입니다.
 *
 * `--- (다음) ---` 구분자를 기준으로 하나 이상의 [DialoguePage]로 분할됩니다.
 *
 * @property speakerTag 원본 화자 태그 (예: "4S17벨슨그", "5S0카만")
 * @property text 원본 텍스트 전체
 * @property pages [text]를 페이지 넘김 구분자로 분할한 페이지 목록
 */
data class DialogueLine(
    val speakerTag: String,
    val text: String,
    val pages: List<DialoguePage>
)

/**
 * 하나의 스토리 씬이나 NPC 대화에 대응하는 대화 세그먼트입니다.
 *
 * @property segmentId 세그먼트 고유 식별 번호 (0부터 시작)
 * @property dialogues 세그먼트에 포함된 대사 라인 목록
 */
data class DialogueSegment(
    val segmentId: Int,
    val dialogues: List<DialogueLine>
) {
    /**
     * 세그먼트 내 모든 대사 라인의 [DialoguePage]들을 순서대로 평탄화(Flatten)하여 반환합니다.
     */
    val allPages: List<DialoguePage> by lazy {
        dialogues.flatMap { it.pages }
    }
}

/**
 * 한 지역(AREA)의 전체 대화 스크립트 데이터입니다.
 *
 * @property file 원본 스크립트 파일명 (예: "AREA00.G&M")
 * @property totalSegments 총 세그먼트 수량
 * @property segments 세그먼트 목록
 */
data class DialogueScript(
    val file: String,
    val totalSegments: Int,
    val segments: List<DialogueSegment>
)
