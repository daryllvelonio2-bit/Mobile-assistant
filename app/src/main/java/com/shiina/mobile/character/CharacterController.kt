package com.shiina.mobile.character

import com.shiina.mobile.decision.Decision
import kotlin.random.Random

/** Pure mapping from decision output to overlay behavior. No Android APIs. */
object CharacterController {

    fun modeFor(decision: Decision): CharacterMode {
        if (!decision.interrupt) return CharacterMode.STAY
        if (decision.action == "hide") return CharacterMode.VANISH
        return CharacterMode.WANDER
    }

    fun wanderOffset(maxX: Int, maxY: Int): Pair<Int, Int> {
        if (maxX <= 0 || maxY <= 0) return 0 to 0
        return Random.nextInt(maxX) to Random.nextInt(maxY)
    }
}
