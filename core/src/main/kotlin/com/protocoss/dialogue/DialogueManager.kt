package com.protocoss.dialogue

/**
 * 인게임 대화의 진행 상태, 타자기 연출(Typewriter effect) 및 사용자 입력에 따른 페이지 넘김을 총괄하는 매니저입니다.
 */
class DialogueManager {

    /**
     * 현재 대화가 활성화되어 화면에 대화창이 열려 있는지 여부입니다.
     */
    var isActive: Boolean = false
        private set

    /**
     * 현재 재생 중인 대화 세그먼트입니다.
     */
    var currentSegment: DialogueSegment? = null
        private set

    /**
     * 현재 세그먼트 내에서 표시 중인 페이지의 인덱스입니다.
     */
    var currentPageIndex: Int = 0
        private set

    /**
     * 현재 표시 중인 대화 페이지입니다.
     */
    val currentPage: DialoguePage?
        get() = currentPages.getOrNull(currentPageIndex)

    /**
     * 현재 페이지에서 화면에 표시될 텍스트입니다. (타자기 효과가 적용된 부분 문자열)
     */
    val visibleText: String
        get() {
            val page = currentPage ?: return ""
            val len = visibleCharCount.coerceAtMost(page.text.length)
            return page.text.substring(0, len)
        }

    /**
     * 현재 페이지의 모든 글자가 화면에 완전히 출력되었는지 여부입니다.
     */
    val isPageComplete: Boolean
        get() {
            val page = currentPage ?: return true
            return visibleCharCount >= page.text.length
        }

    /**
     * 대화가 모두 끝나 대화창이 닫힐 때 호출되는 콜백입니다.
     */
    var onDialogueEnd: (() -> Unit)? = null

    private var currentPages: List<DialoguePage> = emptyList()
    private var visibleCharCount: Int = 0
    private var typeAccumulator: Float = 0f

    /**
     * 초당 출력되는 글자 수입니다. (기본값: 40자/초)
     */
    var charactersPerSecond: Float = 40f

    /**
     * 새로운 대화 세그먼트를 전달받아 대화창을 열고 대화를 시작합니다.
     *
     * @param segment 시작할 대화 세그먼트
     */
    fun startDialogue(segment: DialogueSegment) {
        val pages = segment.allPages
        if (pages.isEmpty()) {
            closeDialogue()
            return
        }

        currentSegment = segment
        currentPages = pages
        currentPageIndex = 0
        resetPageTypewriter()
        isActive = true
    }

    /**
     * 프레임 경과 시간 [delta]에 맞춰 타자기 효과를 갱신합니다.
     *
     * @param delta 이전 프레임으로부터 경과한 초 단위 시간
     */
    fun update(delta: Float) {
        if (!isActive) return

        val page = currentPage ?: return
        if (visibleCharCount < page.text.length) {
            typeAccumulator += delta
            val interval = 1f / charactersPerSecond
            while (typeAccumulator >= interval && visibleCharCount < page.text.length) {
                typeAccumulator -= interval
                visibleCharCount++
            }
        }
    }

    /**
     * 사용자 입력(Space/Enter/클릭)을 받아 현재 페이지를 즉시 완료하거나 다음 페이지로 넘깁니다.
     *
     * @return 대화가 계속 진행 중이면 true, 대화가 완전히 끝나 닫혔으면 false
     */
    fun advance(): Boolean {
        if (!isActive) return false

        val page = currentPage ?: return false
        if (!isPageComplete) {
            // 아직 타이핑 중인 경우: 즉시 모든 텍스트를 한 번에 표시
            visibleCharCount = page.text.length
            return true
        }

        // 이미 완료된 경우: 다음 페이지로 이동
        currentPageIndex++
        if (currentPageIndex < currentPages.size) {
            resetPageTypewriter()
            return true
        } else {
            closeDialogue()
            return false
        }
    }

    /**
     * 대화를 강제로 종료하고 대화창을 닫습니다.
     */
    fun closeDialogue() {
        isActive = false
        currentSegment = null
        currentPages = emptyList()
        currentPageIndex = 0
        visibleCharCount = 0
        typeAccumulator = 0f
        onDialogueEnd?.invoke()
    }

    private fun resetPageTypewriter() {
        visibleCharCount = 0
        typeAccumulator = 0f
    }
}
