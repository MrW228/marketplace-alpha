package com.example.marketplace.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID

class SupportViewModel(
    private val userId: String,
    private val userEmail: String,
    private val messageDao: SupportMessageDao
) : ViewModel() {

    private val telegramBotToken = "8766605841:AAHGGugn5rjDKwQL5VZQ97q4d_OZiFLOHck"
    private val telegramGroupId = "-1004497260559"

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.telegram.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val telegramApi = retrofit.create(TelegramApiService::class.java)

    val messages: StateFlow<List<SupportMessage>> = messageDao.getAllMessages()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var lastUpdateId: Long = 0

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    val response = telegramApi.getUpdates(
                        token = telegramBotToken,
                        offset = if (lastUpdateId == 0L) null else lastUpdateId + 1,
                        timeout = 20
                    )
                    
                    if (response.isSuccessful) {
                        val updates = response.body()?.result
                        if (!updates.isNullOrEmpty()) {
                            processUpdates(updates)
                            lastUpdateId = updates.maxOf { it.updateId }
                        }
                    }
                } catch (e: Exception) {
                    // Log or handle error
                }
                delay(3000) // Poll every 3 seconds
            }
        }
    }

    private suspend fun processUpdates(updates: List<TelegramUpdate>) {
        updates.forEach { update ->
            val msg = update.message
            val replyTo = msg?.replyToMessage
            
            // Check if this is a reply to a user message
            if (msg != null && replyTo != null) {
                val originalText = replyTo.text ?: ""
                // Verify if it's a reply to THIS user
                if (originalText.contains("#user_$userId")) {
                    val adminMessage = SupportMessage(
                        id = msg.messageId.toString(),
                        userId = userId,
                        sender = "admin",
                        text = msg.text ?: "",
                        timestamp = System.currentTimeMillis()
                    )
                    messageDao.insert(adminMessage)
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        val messageId = UUID.randomUUID().toString()
        val message = SupportMessage(
            id = messageId,
            userId = userId,
            sender = "client",
            text = text,
            timestamp = System.currentTimeMillis()
        )

        viewModelScope.launch {
            // 1. Save locally
            messageDao.insert(message)

            // 2. Send to Telegram
            try {
                val telegramText = "<b>Support Request</b>\nUser: $userEmail\nID: #user_${userId}\n\n$text"
                telegramApi.sendMessage(telegramBotToken, telegramGroupId, telegramText)
            } catch (e: Exception) {
                // Log error
            }
        }
    }
}
