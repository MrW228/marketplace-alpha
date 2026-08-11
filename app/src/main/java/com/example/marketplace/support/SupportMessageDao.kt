package com.example.marketplace.support

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SupportMessageDao {
    @Query("SELECT * FROM support_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<SupportMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: SupportMessage)

    @Query("SELECT MAX(id) FROM support_messages WHERE sender = 'admin'")
    suspend fun getLastAdminMessageId(): String?
}
