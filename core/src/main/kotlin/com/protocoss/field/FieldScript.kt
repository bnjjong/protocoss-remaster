package com.protocoss.field

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import com.protocoss.script.EventArchive
import com.protocoss.script.EventMachine
import com.protocoss.script.EventText

/** 현재 패키지가 처리할 수 있는 이벤트 전체를 확인한 뒤 같은 원본 명령으로 실행한다. */
class FieldScript(private val archive:EventArchive,source:DosSave,argument:Int,private val rewards:FieldRewards?=null) {
    data class Blocked(val offset:Int,val reason:String)
    val machine=EventMachine(archive,IntArray(300) { source.variable(it) },argument,true)
    private var state=source
    val save get()=state.withVariables(machine.variables().mapIndexed { i,v -> i to v }.toMap())
    fun next(maxInstructions:Int=10000)=machine.run(maxInstructions)
    fun resumeDialogue()=machine.resumeDialogue()
    fun resumeService()=machine.resumeService()
    fun child(argument:Int)=FieldScript(archive,save,argument,rewards)
    fun adopt(other:DosSave) {
        state=other
        for(i in 0 until 300) machine.setVariable(i,other.variable(i))
    }
    fun changeParty(request:EventMachine.Stop.Party) {
        check(machine.pending===request)
        state=FieldParty.change(save,request.character,request.joining);machine.resumeService()
    }
    fun beginAnimation(request:EventMachine.Stop.Animation,story:FieldStory):FieldStory.Plan {
        check(machine.pending===request && machine.returnAddresses().isEmpty()) { "연출 호출 스택을 연결하지 않았습니다." }
        val plan=story.plan(request,save)
        machine.setVariable(4,request.music);state=story.begin(save,request,plan)
        return plan
    }
    fun finishAnimation(request:EventMachine.Stop.Animation,story:FieldStory,plan:FieldStory.Plan) {
        check(machine.pending===request)
        state=story.finish(save,request,plan);machine.resumeService()
    }
    private var credited:EventMachine.Stop.Reward?=null
    fun receiveReward(request:EventMachine.Stop.Reward) {
        if(credited===request) return
        check(machine.pending===request) { "보상 명령 대기 중이 아닙니다." }
        state=checkNotNull(rewards).receive(save,request);credited=request
    }
    fun rewardPages(request:EventMachine.Stop.Reward)=checkNotNull(rewards).pages(request)
    var openedDoor=false;private set
    fun resumeDoor(pose:Int=0,layer:Int=save.layer) {
        require(pose in listOf(0,8))
        machine.resumeDoor();state=save.withFieldState(layer,pose);openedDoor=true
    }
    fun warp(request:EventMachine.Stop.Warp) {
        state=save.travel(request.map,request.scrollX,request.scrollY,request.x,request.y,request.pose,request.layer,mapOf(4 to request.music))
        machine.resumeService()
    }
    fun place(request:EventMachine.Stop.Place) { state=save.withLocation(EventText.decode(request.bytes())) }

    companion object {
        /** 사전 검사는 변수와 저장 사본만 사용한다. 미지원 시 게임·음량·인물 상태를 바꾸지 않는다. */
        fun prepare(archive:EventArchive,source:DosSave,argument:Int,maps:JsonValue,characters:Set<Char>,
                    allowWarp:Boolean,canOpenDoor:(Int)->Boolean = { false },rewards:FieldRewards?=null,story:FieldStory?=null,blocked:(Blocked)->Unit = {}):FieldScript? {
            val trial=FieldScript(archive,source,argument,rewards)
            var warps=0
            var spent=0L
            fun next(script:FieldScript):EventMachine.Stop {
                require(spent<10000) { "필드 이벤트의 명령 수 한도를 넘었습니다." }
                val before=script.machine.executedInstructions;val request=script.next((10000-spent).toInt())
                spent+=script.machine.executedInstructions-before;return request
            }
            fun text(pages:JsonValue) {
                for(page in pages) {
                    require(page.getInt("portrait") in listOf(-1,0,1,17)) { "초상화 자산이 없습니다." }
                    require((page.getString("speaker")+page.getString("text")).all { it=='\n' || it in characters }) { "글꼴 자산이 없습니다." }
                }
            }
            try {
                while(spent<10000) {
                    when(val request=next(trial)) {
                        is EventMachine.Stop.Dialogue -> { text(EventText.pages(request.bytes()));trial.resumeDialogue() }
                        is EventMachine.Stop.Warp -> {
                            require(allowWarp && !trial.openedDoor && ++warps==1) { "이 호출에서는 이동을 연결하지 않았습니다." }
                            require(source.area==0) { "이 지역의 필드를 연결하지 않았습니다." }
                            val map=maps.get(request.map.toString()) ?: error("목적지 맵 자산이 없습니다.")
                            require(request.scrollX*8+request.x-16 in 0..map.getInt("width")*8-24 &&
                                request.scrollY*8+request.y-16 in 0..map.getInt("height")*8-24) { "목적지 인물 좌표가 맵 밖입니다." }
                            require(request.music in setOf(10,24,25)) { "목적지 음악을 연결하지 않았습니다." }
                            trial.warp(request)
                        }
                        is EventMachine.Stop.Animation -> {
                            require(allowWarp && story!=null) { "이 호출에서는 연출을 연결하지 않았습니다." }
                            val plan=trial.beginAnimation(request,story)
                            for(argument in plan.events.values) {
                                val child=trial.child(argument)
                                childRun@while(true) when(val nested=next(child)) {
                                    is EventMachine.Stop.Dialogue -> { text(EventText.pages(nested.bytes()));child.resumeDialogue() }
                                    is EventMachine.Stop.Party -> child.changeParty(nested)
                                    is EventMachine.Stop.Finished -> break@childRun
                                    else -> error("연출 안의 이 명령은 연결하지 않았습니다.")
                                }
                                trial.adopt(child.save)
                            }
                            trial.finishAnimation(request,story,plan)
                        }
                        is EventMachine.Stop.Party -> trial.changeParty(request)
                        is EventMachine.Stop.Reward -> { text(trial.rewardPages(request));trial.receiveReward(request);trial.resumeService() }
                        is EventMachine.Stop.Panel -> { require(rewards!=null) { "보상 안내창을 연결하지 않았습니다." };trial.resumeService() }
                        is EventMachine.Stop.Door -> {
                            require(request.id==0 && warps==0 && !trial.openedDoor && canOpenDoor(request.id)) { "이 위치의 문 명령을 연결하지 않았습니다." }
                            trial.resumeDoor()
                        }
                        is EventMachine.Stop.Place -> {
                            val name=EventText.decode(request.bytes())
                            require(name.all { it in characters }) { "장소명 글꼴 자산이 없습니다." }
                            trial.place(request);trial.resumeService()
                        }
                        is EventMachine.Stop.Effect -> { require(request.id==0) { "이 효과음을 연결하지 않았습니다." };trial.resumeService() }
                        is EventMachine.Stop.Finished -> return FieldScript(archive,source,argument,rewards)
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
