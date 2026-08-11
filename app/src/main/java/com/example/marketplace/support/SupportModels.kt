package com.example.marketplace.support

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "support_messages")
data class SupportMessage(
    @PrimaryKey val id: String = "",
    val userId: String = "",
    val sender: String = "", // "client" or "admin"
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null
)

data class TelegramUpdate(
    @SerializedName("update_id") val updateId: Long,
    val message: TelegramMessage? = null
)

data class TelegramMessage(
    @SerializedName("message_id") val messageId: Long,
    val text: String? = null,
    @SerializedName("reply_to_message") val replyToMessage: TelegramMessage? = null
)
