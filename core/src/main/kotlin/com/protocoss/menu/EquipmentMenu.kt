package com.protocoss.menu

import com.protocoss.save.DosSave

/** 파티원 → 여섯 장비 슬롯 → 선택 목록. 해제는 원작처럼 목록 진입 때 일어난다. */
class EquipmentMenu(initialSlot:Int=0,initialCharacter:Int=0) {
    private val firstSlot=initialSlot
    enum class Stage { HOME, SLOT, SELECT }
    var stage=Stage.HOME; private set
    var selected=0; private set
    var scroll=0; private set
    var slot=initialSlot; private set
    var character=initialCharacter; private set
    var stepChange:Int?=null; private set
    fun navigate(direction:String,model:Equipment,save:DosSave) {
        when(stage) {
            Stage.HOME -> if(direction=="up" || direction=="down") {
                val party=model.party(save);if(party.isEmpty()) return
                val index=party.indexOf(character).coerceAtLeast(0)
                character=party[(index+(if(direction=="up") -1 else 1)+party.size)%party.size]
            }
            Stage.SLOT -> slot=model.data.get("slot_navigation").get(direction)[slot].asInt()
            Stage.SELECT -> if(direction=="up" || direction=="down") move(if(direction=="up") -1 else 1,model.candidates(save,character,slot).size)
        }
    }
    fun move(direction:Int,count:Int) {
        if(stage!=Stage.SELECT || count==0) return
        selected=(selected+direction).coerceIn(0,count-1)
        scroll=scroll.coerceIn((selected-3).coerceAtLeast(0),selected)
    }
    fun confirm(model:Equipment,save:DosSave):DosSave {
        stepChange=null
        return when(stage) {
        Stage.HOME -> { slot=firstSlot;stage=Stage.SLOT;save }
        Stage.SLOT -> {
            stage=Stage.SELECT;selected=0;scroll=0
            if(model.equipped(save,character,slot)==157) stepChange=1
            model.beginSlot(save,character,slot)
        }
        Stage.SELECT -> {
            val choices=model.candidates(save,character,slot)
            if(choices.isEmpty()) { stage=Stage.SLOT;save }
            else if(model.canEquip(save,choices[selected].id,character,slot)) {
                val item=choices[selected].id
                if(item==157) stepChange=2
                stage=Stage.SLOT;model.equip(save,item,character,slot)
            }
            else save
        }
        }
    }
    fun back():Boolean {
        if(stage==Stage.HOME) return true
        stage=if(stage==Stage.SELECT) Stage.SLOT else Stage.HOME
        return false
    }
}
