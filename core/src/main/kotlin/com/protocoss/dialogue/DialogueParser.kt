package com.protocoss.dialogue

/**
 * DOS 원작 대화 스크립트의 화자 태그와 텍스트를 현대적 객체 모델로 변환하는 파서입니다.
 */
object DialogueParser {

    private val KNOWN_CHARACTERS = mapOf(
        "벨슨그" to "벨슨",
        "벨슨" to "벨슨",
        "카만" to "카만",
        "엔키드" to "엔키드",
        "세레스" to "세레스",
        "아키라" to "아키라",
        "칼리토" to "칼리토",
        "메딜" to "메딜"
    )

    private val PAGE_SPLITTER = Regex("""\s*--- \(다음\) ---\s*""")
    private val TAG_PATTERN = Regex("""^[0-9]*S?([0-9]+)?(.*)$""")

    /**
     * 화자 태그 문자열에서 초상화 식별 번호와 정제된 화자 이름을 추출합니다.
     *
     * @param tag 원본 화자 태그 (예: "4S17벨슨그", "5S0카만")
     * @return 초상화 ID와 정제된 이름의 쌍 (Pair(portraitId, speakerName))
     */
    fun parseSpeakerTag(tag: String): Pair<Int, String> {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return Pair(0, "")

        val match = TAG_PATTERN.matchEntire(trimmed)
        if (match != null) {
            val portraitId = match.groupValues[1].toIntOrNull() ?: 0
            val rawName = match.groupValues[2].trim()
            val cleanName = cleanSpeakerName(rawName)
            return Pair(portraitId, cleanName)
        }

        return Pair(0, cleanSpeakerName(trimmed))
    }

    /**
     * 조합형 한글 디코딩 시 발생한 말미 불필요 접미사를 보정하여 올바른 이름을 반환합니다.
     *
     * @param raw 정제 전 화자 이름
     * @return 정제된 한글 화자 이름
     */
    fun cleanSpeakerName(raw: String): String {
        val trimmed = raw.trim()
        return KNOWN_CHARACTERS[trimmed] ?: trimmed
    }

    /**
     * 원본 대사 텍스트를 `--- (다음) ---` 구분자 기준으로 분할하여 [DialoguePage] 목록을 생성합니다.
     *
     * @param speakerTag 화자 태그
     * @param rawText 원본 대사 전체 텍스트
     * @return 화면 표시용 페이지 목록
     */
    fun parsePages(speakerTag: String, rawText: String): List<DialoguePage> {
        val (portraitId, speakerName) = parseSpeakerTag(speakerTag)
        val rawPages = rawText.split(PAGE_SPLITTER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (rawPages.isEmpty()) {
            return listOf(DialoguePage(speakerName, rawText.trim(), portraitId))
        }

        return rawPages.map { pageText ->
            DialoguePage(speakerName, pageText, portraitId)
        }
    }
}
