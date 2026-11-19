package com.protocoss.battle

class PracticeBattle {
    enum class Action { ATTACK, GUARD }
    enum class State { ACTIVE, WON, LOST }

    data class Turn(val damageDealt: Int, val damageTaken: Int)

    var playerHealth = PLAYER_MAX_HEALTH
        private set
    var enemyHealth = ENEMY_MAX_HEALTH
        private set
    var round = 1
        private set

    val state: State
        get() = when {
            enemyHealth == 0 -> State.WON
            playerHealth == 0 -> State.LOST
            else -> State.ACTIVE
        }

    val nextEnemyDamage: Int
        get() = if (round % 2 == 0) 14 else 5

    fun act(action: Action): Turn? {
        if (state != State.ACTIVE) return null

        val damageDealt = if (action == Action.ATTACK) minOf(enemyHealth, PLAYER_DAMAGE) else 0
        enemyHealth -= damageDealt

        val incomingDamage = when {
            enemyHealth == 0 -> 0
            action == Action.GUARD -> nextEnemyDamage / 4
            else -> nextEnemyDamage
        }
        val damageTaken = minOf(playerHealth, incomingDamage)
        playerHealth -= damageTaken

        if (state == State.ACTIVE) round++
        return Turn(damageDealt, damageTaken)
    }

    companion object {
        const val PLAYER_MAX_HEALTH = 18
        const val ENEMY_MAX_HEALTH = 26
        private const val PLAYER_DAMAGE = 9
    }
}
