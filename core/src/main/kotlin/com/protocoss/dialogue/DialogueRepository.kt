package com.protocoss.dialogue

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * 지역별 대화 스크립트 JSON 데이터를 로드하고 인게임 세그먼트별로 인덱싱하여 제공하는 저장소입니다.
 */
class DialogueRepository {

    private val segmentsById = mutableMapOf<Int, DialogueSegment>()

    /**
     * 로드된 총 대화 세그먼트 수량입니다.
     */
    val size: Int get() = segmentsById.size

    /**
     * 내부 에셋 경로로부터 대화 스크립트 JSON 파일을 읽어 저장소에 등록합니다.
     *
     * @param internalPath LibGDX 에셋 내부 경로 (예: "assets/data/scripts/area00_scripts.json")
     */
    fun loadFromAssets(internalPath: String) {
        val fileHandle = Gdx.files.internal(internalPath)
        loadFromJson(fileHandle.readString("UTF-8"))
    }

    /**
     * JSON 문자열을 직접 파싱하여 저장소에 대화 세그먼트들을 로드합니다.
     *
     * @param jsonContent 파싱할 스크립트 JSON 문자열 본문
     */
    fun loadFromJson(jsonContent: String) {
        val root: JsonValue = JsonReader().parse(jsonContent)
        val segmentsNode = root.get("segments") ?: return

        var segmentChild = segmentsNode.child
        while (segmentChild != null) {
            val segmentId = segmentChild.getInt("segment_id", -1)
            val dialoguesNode = segmentChild.get("dialogues")
            val dialogueLines = mutableListOf<DialogueLine>()

            if (dialoguesNode != null) {
                var dialogueChild = dialoguesNode.child
                while (dialogueChild != null) {
                    val speakerTag = dialogueChild.getString("speaker_tag", "")
                    val text = dialogueChild.getString("text", "")
                    val pages = DialogueParser.parsePages(speakerTag, text)
                    dialogueLines.add(DialogueLine(speakerTag, text, pages))
                    dialogueChild = dialogueChild.next
                }
            }

            val segment = DialogueSegment(segmentId, dialogueLines)
            segmentsById[segmentId] = segment
            segmentChild = segmentChild.next
        }
    }

    /**
     * 지정한 [segmentId]에 해당하는 대화 세그먼트를 조회합니다.
     *
     * @param segmentId 조회할 세그먼트 식별 번호
     * @return 대화 세그먼트 또는 존재하지 않을 경우 null
     */
    fun getSegment(segmentId: Int): DialogueSegment? = segmentsById[segmentId]

    /**
     * 저장된 모든 세그먼트를 비웁니다.
     */
    fun clear() {
        segmentsById.clear()
    }
}
