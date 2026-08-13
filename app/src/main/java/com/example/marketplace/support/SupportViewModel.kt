package com.example.marketplace.support

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

class SupportViewModel(
    private val userId: String,
    private val userEmail: String,
    private val messageDao: SupportMessageDao
) : ViewModel() {

    private val telegramBotToken = "8766605841:AAHGGugn5rjDKwQL5VZQ97q4d_OZiFLOHck"
    private val telegramGroupId = "-1004497260559"

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(logging)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.telegram.org/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val telegramApi = retrofit.create(TelegramApiService::class.java)

    val messages: StateFlow<List<SupportMessage>> = messageDao.getAllMessages(userId)
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
        Log.d("SupportChat", "Starting polling for user $userId")
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
                        Log.d("SupportChat", "Received ${updates?.size ?: 0} updates")
                        if (!updates.isNullOrEmpty()) {
                            processUpdates(updates)
                            lastUpdateId = updates.maxOf { it.updateId }
                        }
                    } else {
                        val errorBody = response.errorBody()?.string()
                        Log.e("SupportChat", "Polling error: ${response.code()} - $errorBody")
                        if (response.code() == 409) {
                            Log.e("SupportChat", "Conflict! Webhook might be active. Delete it using deleteWebhook API.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SupportChat", "Polling exception", e)
                }
                delay(3000)
            }
        }
    }

    private suspend fun processUpdates(updates: List<TelegramUpdate>) {
        updates.forEach { update ->
            val msg = update.message
            val replyTo = msg?.replyToMessage
            
            Log.d("SupportChat", "Processing update ${update.updateId}, hasReply: ${replyTo != null}")

            // Check if this is a reply to a user message
            if (msg != null && replyTo != null) {
                val originalText = replyTo.text ?: ""
                Log.d("SupportChat", "Reply to: $originalText")
                // Verify if it's a reply to THIS user
                if (originalText.contains("#user_$userId")) {
                    Log.d("SupportChat", "Found reply for this user: ${msg.text}")
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
        Log.d("SupportChat", "Sending message: $text")
        
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
            Log.d("SupportChat", "Message saved locally")

            // 2. Send to Telegram
            try {
                val telegramText = "<b>Support Request</b>\nUser: $userEmail\nID: #user_${userId}\n\n$text"
                val response = telegramApi.sendMessage(telegramBotToken, telegramGroupId, telegramText)
                if (response.isSuccessful) {
                    Log.d("SupportChat", "Message sent to Telegram successfully")
                } else {
                    Log.e("SupportChat", "Failed to send to Telegram: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("SupportChat", "Exception sending message", e)
            }
        }
    }
}
