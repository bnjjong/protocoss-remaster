package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import com.protocoss.script.EventArchive
import com.protocoss.script.EventMachine
import com.protocoss.script.EventText

/** 현재 패키지가 처리할 수 있는 이벤트 전체를 확인한 뒤 같은 원본 명령으로 실행한다. */
class FieldScript(archive:EventArchive,source:DosSave,argument:Int) {
    data class Blocked(val offset:Int,val reason:String)
    val machine=EventMachine(archive,IntArray(300) { source.variable(it) },argument,true)
    private var state=source
    val save get()=state.withVariables(machine.variables().mapIndexed { i,v -> i to v }.toMap())
    fun next(maxInstructions:Int=10000)=machine.run(maxInstructions)
    fun resumeDialogue()=machine.resumeDialogue()
    fun resumeService()=machine.resumeService()
    fun warp(request:EventMachine.Stop.Warp) {
        state=save.travel(request.map,request.scrollX,request.scrollY,request.x,request.y,request.pose,request.layer,mapOf(4 to request.music))
        machine.resumeService()
    }
    fun place(request:EventMachine.Stop.Place) { state=save.withLocation(EventText.decode(request.bytes())) }

    companion object {
        /** 사전 검사는 변수와 저장 사본만 사용한다. 미지원 시 게임·음량·인물 상태를 바꾸지 않는다. */
        fun prepare(archive:EventArchive,source:DosSave,argument:Int,maps:JsonValue,characters:Set<Char>,
                    allowWarp:Boolean,blocked:(Blocked)->Unit = {}):FieldScript? {
            val trial=FieldScript(archive,source,argument)
            var warps=0
            fun text(pages:JsonValue) {
                for(page in pages) {
                    require(page.getInt("portrait") in listOf(-1,0,1,17)) { "초상화 자산이 없습니다." }
                    require((page.getString("speaker")+page.getString("text")).all { it=='\n' || it in characters }) { "글꼴 자산이 없습니다." }
                }
            }
            try {
                while(trial.machine.executedInstructions<10000) {
                    when(val request=trial.next(10000-trial.machine.executedInstructions.toInt())) {
                        is EventMachine.Stop.Dialogue -> { text(EventText.pages(request.bytes()));trial.resumeDialogue() }
                        is EventMachine.Stop.Warp -> {
                            require(allowWarp && ++warps==1) { "이 호출에서는 이동을 연결하지 않았습니다." }
                            require(source.area==0) { "이 지역의 필드를 연결하지 않았습니다." }
                            val map=maps.get(request.map.toString()) ?: error("목적지 맵 자산이 없습니다.")
                            require(request.scrollX*8+request.x-16 in 0..map.getInt("width")*8-24 &&
                                request.scrollY*8+request.y-16 in 0..map.getInt("height")*8-24) { "목적지 인물 좌표가 맵 밖입니다." }
                            require(request.music in setOf(10,24,25)) { "목적지 음악을 연결하지 않았습니다." }
                            trial.warp(request)
                        }
                        is EventMachine.Stop.Place -> {
                            val name=EventText.decode(request.bytes())
                            require(name.all { it in characters }) { "장소명 글꼴 자산이 없습니다." }
                            trial.place(request);trial.resumeService()
                        }
                        is EventMachine.Stop.Effect -> { require(request.id==0) { "이 효과음을 연결하지 않았습니다." };trial.resumeService() }
                        is EventMachine.Stop.Finished -> return FieldScript(archive,source,argument)
                        is EventMachine.Stop.Unhandled -> { blocked(Blocked(request.offset,"미지원 명령 ${request.opcode.toString(16)}"));return null }
                        is EventMachine.Stop.Budget -> error("필드 이벤트의 명령 수 한도를 넘었습니다.")
                    }
                }
                error("필드 이벤트가 끝나지 않았습니다.")
            } catch(error:IllegalArgumentException) { blocked(Blocked(trial.machine.offset,error.message ?: "필드 인자 오류")) }
            catch(error:java.nio.charset.CharacterCodingException) { blocked(Blocked(trial.machine.offset,"대화 문자 인코딩을 해석하지 못했습니다.")) }
            catch(error:IllegalStateException) { blocked(Blocked(trial.machine.offset,error.message ?: "필드 명령 오류")) }
            return null
        }
    }
}
