package com.protocoss.battle

import com.protocoss.battle.PracticeBattle.Action.ATTACK
import com.protocoss.battle.PracticeBattle.Action.GUARD
import com.protocoss.battle.PracticeBattle.State
import com.protocoss.battle.PracticeBattle.Turn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PracticeBattleTest {
    @Test
    fun guardingTheHeavyAttackAllowsThePlayerToWin() {
        val battle = PracticeBattle()
        assertEquals(5, battle.nextEnemyDamage)
        assertEquals(Turn(9, 5), battle.act(ATTACK))
        assertEquals(14, battle.nextEnemyDamage)
        assertEquals(Turn(0, 3), battle.act(GUARD))
        assertEquals(Turn(9, 5), battle.act(ATTACK))
        assertEquals(Turn(8, 0), battle.act(ATTACK))
        assertEquals(State.WON, battle.state)
        assertEquals(5, battle.playerHealth)
        assertEquals(0, battle.enemyHealth)
        assertNull(battle.act(ATTACK))
    }

    @Test
    fun healthStopsAtZeroAndDefeatStopsFurtherTurns() {
        val battle = PracticeBattle()
        battle.act(ATTACK)
        assertEquals(Turn(9, 13), battle.act(ATTACK))
        assertEquals(State.LOST, battle.state)
        assertNull(battle.act(GUARD))
        assertEquals(0, battle.playerHealth)
        assertEquals(8, battle.enemyHealth)
        assertEquals(2, battle.round)
    }
}
