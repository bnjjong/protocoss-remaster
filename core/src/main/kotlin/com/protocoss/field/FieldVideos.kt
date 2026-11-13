package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import com.protocoss.script.EventMachine
import com.protocoss.script.EventText

/** 원본 영상 인자와 검증한 패키지 영상의 대응. 조우와 출발 영상의 검증한 조합만 연결한다. */
class FieldVideos(private val data:JsonValue) {
    fun plan(request:EventMachine.Stop.Video,save:DosSave,nested:Boolean=true):JsonValue {
        val name=EventText.decode(request.bytes())
        val row=data.get("videos").firstOrNull { it.getString("name").equals(name,true) } ?: error("이 영상 자산을 연결하지 않았습니다.")
        require(nested==row.getBoolean("nested",true)) { "이 영상의 호출 위치는 연결하지 않았습니다." }
        require(save.area==0 && save.variable(4)==row.getInt("return_music")) { "이 영상의 호출 전 필드 음악을 연결하지 않았습니다." }
        require(request.ticks==row.getInt("ticks") && request.music==row.getInt("music") && request.restore==row.getInt("restore")) { "이 영상의 재생 인자 조합을 연결하지 않았습니다." }
        return row
    }
}
