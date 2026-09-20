package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** One Talk exchange turn (Phase 6 chat continuity). Pruned to last 20. */
@Entity(tableName = "chat_turns")
data class ChatTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val timestampMillis: Long,
)

@Dao
interface ChatTurnDao {
    @Insert
    suspend fun insert(turn: ChatTurn)

    @Query("SELECT * FROM chat_turns ORDER BY id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ChatTurn>

    @Query("SELECT COUNT(*) FROM chat_turns WHERE timestampMillis >= :startMillis AND timestampMillis < :endMillis")
    suspend fun countInRange(startMillis: Long, endMillis: Long): Int

    @Query("DELETE FROM chat_turns WHERE id NOT IN (SELECT id FROM chat_turns ORDER BY id DESC LIMIT :keep)")
    suspend fun keepLatest(keep: Int)

    @Query("DELETE FROM chat_turns")
    suspend fun deleteAll()
}

/** Talk thread continuity: last 30 turns kept, last 6 fed into the prompt. */
class ChatHistory(private val dao: ChatTurnDao) {

    suspend fun addUser(text: String) = add("user", text)

    suspend fun addShiina(text: String) = add("shiina", text)

    private suspend fun add(role: String, text: String) {
        dao.insert(ChatTurn(role = role, text = text.take(512), timestampMillis = System.currentTimeMillis()))
        dao.keepLatest(30)
    }

    /** Last turns in chronological order for prompt injection. */
    suspend fun recentTurns(limit: Int = 6): List<ChatTurn> = dao.latest(limit).reversed()
}