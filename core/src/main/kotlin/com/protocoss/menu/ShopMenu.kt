package com.protocoss.menu

import com.badlogic.gdx.utils.JsonValue
import com.protocoss.save.DosSave
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 엘가임 잡화점 0번 가격 표와 원작의 구입·판매 수량 처리. */
class Shop(val data:JsonValue) {
    data class Price(val id:Int,val buy:Int,val sell:Int)
    val prices=data.get("prices").map { Price(it.getInt("id"),it.getInt("buy"),it.getInt("sell")) }
    fun money(save:DosSave)=ByteBuffer.wrap(save.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(119)
    fun owned(save:DosSave,item:Int)=save.bytes()[123+item].toInt() and 255
    fun candidates(save:DosSave,sell:Boolean)=prices.filter { if(sell) owned(save,it.id)>0 else it.buy>0 }.map { it.id }
    fun price(item:Int,sell:Boolean)=prices[item].let { if(sell) it.sell else it.buy }
    fun trade(save:DosSave,item:Int,sell:Boolean,quantity:Int):DosSave {
        val b=save.bytes();b[123+item]=(owned(save,item)+if(sell) -quantity else quantity).toByte()
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(119,money(save)+(if(sell) 1 else -1)*quantity*price(item,sell))
        return DosSave.parse(b)
    }
}

class ShopMenu {
    enum class Stage { HOME, CATALOG, AMOUNT, TRADE }
    var stage=Stage.HOME;private set
    var choice=0;private set
    var selected=0;private set
    var quantity=0;private set
    private var elapsed=0f
    val blink get()=(elapsed*70f/4).toInt()%2==0
    fun tick(delta:Float) { elapsed+=delta;effect=null }
    var animation=0;private set
    private var animationTime=0f
    private var pending:DosSave?=null
    var closed=false;private set
    var effect:Int?=null;private set
    val sell get()=choice==3
    fun item(model:Shop,save:DosSave)=model.candidates(save,sell)[selected]
    fun navigate(direction:String,model:Shop,save:DosSave) {
        effect=null
        when(stage) {
            Stage.TRADE -> return
            Stage.HOME -> if(direction=="up" || direction=="down") {
                choice=(choice+(if(direction=="up") 3 else 6))%9;effect=11
            }
            Stage.CATALOG -> if(direction=="left" || direction=="right") {
                val next=(selected+if(direction=="left") -1 else 1).coerceIn(0,model.candidates(save,sell).lastIndex)
                if(next!=selected) { selected=next;effect=12 }
            }
            Stage.AMOUNT -> {
                val id=item(model,save);val owned=model.owned(save,id);val price=model.price(id,sell);val money=model.money(save)
                val before=quantity
                when(direction) {
                    "left" -> if(quantity>0) quantity--
                    "down" -> if(quantity>0) quantity=if(quantity>9) quantity-10 else 0
                    "right" -> if(sell) { if(quantity<owned) quantity++ }
                        else if(owned+quantity<99 && money-(quantity+1)*price>=0) quantity++
                    "up" -> if(sell) quantity=if(owned-10>quantity) quantity+10 else maxOf(quantity,owned)
                        else if(owned+quantity<90) {
                            quantity=if(money-(quantity+10)*price>=0) quantity+10 else money/price
                            effect=12
                        } else if(owned+quantity<99) {
                            // 원작 0x28060은 잔액 대신 금액의 부호만 검사한다. 이 경우 음수 잔액도 생긴다.
                            quantity=99-owned
                            if(quantity*price<0) { quantity=money/price;effect=12 }
                            return
                        }
                }
                if(quantity!=before) effect=12
            }
        }
    }
    fun confirm(model:Shop,save:DosSave,space:Boolean=false):DosSave {
        effect=null
        if(stage==Stage.TRADE) return save
        if(space && stage!=Stage.HOME) return save
        effect=8
        when(stage) {
            Stage.TRADE -> return save
            Stage.HOME -> if(choice==6) closed=true
                else if(model.candidates(save,sell).isNotEmpty()) { selected=0;stage=Stage.CATALOG }
            Stage.CATALOG -> {
                val id=item(model,save)
                quantity=if(sell || (model.owned(save,id)<99 && model.money(save)-model.price(id,false)>=0)) 1 else 0
                stage=Stage.AMOUNT
            }
            Stage.AMOUNT -> {
                val result=if(quantity==0) save else model.trade(save,item(model,save),sell,quantity)
                if(quantity!=0) { pending=result;animation=0;animationTime=0f;stage=Stage.TRADE;return save }
                val choices=model.candidates(result,sell)
                if(choices.isEmpty()) stage=Stage.HOME
                else { stage=Stage.CATALOG;selected=selected.coerceAtMost(choices.lastIndex) }
                return result
            }
        }
        return save
    }
    fun update(delta:Float,model:Shop,save:DosSave):DosSave {
        effect=null
        if(stage!=Stage.TRADE) return save
        animationTime+=delta*70f
        val delays=intArrayOf(0,2,2,2,1,1,0,0,0,0,0,0)
        while(animation<12 && animationTime+0.0001f>=delays[animation]) {
            animationTime-=delays[animation];animation++
            if(animation==5) effect=7
        }
        if(animation<12) return save
        val result=requireNotNull(pending);pending=null
        val choices=model.candidates(result,sell)
        if(choices.isEmpty()) stage=Stage.HOME
        else { stage=Stage.CATALOG;selected=selected.coerceAtMost(choices.lastIndex) }
        return result
    }
    fun back() {
        effect=null
        if(stage==Stage.TRADE) return
        effect=8
        when(stage) { Stage.TRADE->Unit;Stage.HOME->closed=true;Stage.CATALOG->stage=Stage.HOME;Stage.AMOUNT->stage=Stage.CATALOG }
    }
}
