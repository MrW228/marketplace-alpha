package com.example.marketplace.support

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SupportMessageDao {
    @Query("SELECT * FROM support_messages WHERE userId = :userId ORDER BY timestamp ASC")
    fun getAllMessages(userId: String): Flow<List<SupportMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: SupportMessage)
}
