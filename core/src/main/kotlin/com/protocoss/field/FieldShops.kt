package com.protocoss.field

import com.protocoss.save.DosSave
import com.protocoss.script.EventMachine

/** 검증한 엘가임 잡화점 가격표만 기존 거래 화면에 연결한다. */
object FieldShops {
    fun supported(save:DosSave,request:EventMachine.Stop.Shop)=save.area==0 && request.priceTable==0 && request.kind==0
}
