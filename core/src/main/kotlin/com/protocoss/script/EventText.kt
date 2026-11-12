package com.protocoss.script

import com.badlogic.gdx.utils.JsonValue
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 원본 대화의 화자·초상화·줄바꿈·페이지 유지 규칙. 글꼴과 화면 배치는 렌더러가 맡는다. */
object EventText {
    fun decode(bytes:ByteArray):String = Charset.forName("x-Johab").newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    fun pages(bytes:ByteArray):JsonValue {
        val text=decode(bytes);val pages=JsonValue(JsonValue.ValueType.array)
        var speaker="";var portrait=-1;var lines=mutableListOf("");var p=0
        fun page() {
            pages.addChild(JsonValue(JsonValue.ValueType.`object`).apply {
                addChild("speaker",JsonValue(speaker));addChild("portrait",JsonValue(portrait.toLong()))
                addChild("text",JsonValue(lines.takeLast(2).joinToString("\n")))
            })
        }
        while(p<text.length) {
            if(text[p]!='/') { val last=lines.lastIndex;lines[last]=lines[last]+text[p];p++;continue }
            require(p+1<text.length) { "대화 명령이 잘렸습니다." }
            when(text[p+1]) {
                '[' -> {
                    val end=text.indexOf(']',p+2);require(end>=0) { "대화 화자 태그가 잘렸습니다." }
                    val tag=Regex("(\\d+)(?:S(\\d+))?([^]]*)").matchEntire(text.substring(p+2,end))
                        ?: error("미지원 대화 화자 태그")
                    portrait=tag.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: -1
                    speaker=tag.groupValues[3];lines=mutableListOf("");p=end+1
                }
                'N' -> { lines.add("");p+=2 }
                'D','E' -> { page();lines=mutableListOf(lines.last(),"");p+=2 }
                else -> error("미지원 대화 명령: ${text.substring(p,minOf(text.length,p+2))}")
            }
        }
        require(pages.size>0) { "대화 종료·페이지 명령이 없습니다." }
        return pages
    }
}
