package com.protocoss.menu

import com.protocoss.save.DosSave

/** 장비 탭 → 무기 슬롯 → 선택 목록. 해제는 원작처럼 선택 목록 진입 때 일어난다. */
class WeaponMenu {
    enum class Stage { HOME, SLOT, SELECT }
    var stage=Stage.HOME; private set
    var selected=0; private set
    var scroll=0; private set
    fun move(direction:Int,count:Int) {
        if(stage!=Stage.SELECT || count==0) return
        selected=(selected+direction).coerceIn(0,count-1)
        scroll=scroll.coerceIn((selected-3).coerceAtLeast(0),selected)
    }
    fun confirm(model:Equipment,save:DosSave):DosSave = when(stage) {
        Stage.HOME -> { stage=Stage.SLOT;save }
        Stage.SLOT -> { stage=Stage.SELECT;selected=0;scroll=0;model.beginWeapon(save) }
        Stage.SELECT -> {
            val choices=model.candidates(save)
            if(choices.isEmpty()) { stage=Stage.SLOT;save }
            else if(model.canEquip(save,choices[selected].id)) { stage=Stage.SLOT;model.equip(save,choices[selected].id) }
            else save
        }
    }
    fun back():Boolean {
        if(stage==Stage.HOME) return true
        stage=if(stage==Stage.SELECT) Stage.SLOT else Stage.HOME
        return false
    }
}
