package com.example.migration

import com.example.model.ItemKind
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

data class ParsedWhatsAppMessage(
    val id: String = UUID.randomUUID().toString(),
    val rawText: String,
    val sender: String?,
    val dateMillis: Long,
    val isMediaAttachment: Boolean,
    val attachmentFileName: String?,
    val detectedKind: ItemKind,
    val extractedUrl: String?
)

data class MigrationResult(
    val totalMessages: Int,
    val textNotes: Int,
    val links: Int,
    val mediaPlaceholders: Int,
    val parsedItems: List<ParsedWhatsAppMessage>
)

object WhatsAppMigrationParser {

    private val DATE_PATTERNS = listOf(
        SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.US),
        SimpleDateFormat("dd/MM/yy, HH:mm", Locale.US),
        SimpleDateFormat("MM/dd/yy, hh:mm a", Locale.US),
        SimpleDateFormat("dd/MM/yyyy, hh:mm a", Locale.US),
        SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US),
        SimpleDateFormat("dd/MM/yy, h:mm a", Locale.US)
    )

    private val URL_REGEX = "https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+".toRegex(RegexOption.IGNORE_CASE)

    fun parse(chatText: String): MigrationResult {
        val lines = chatText.lines()
        val messages = mutableListOf<ParsedWhatsAppMessage>()

        var currentSender: String? = null
        var currentDateMillis = System.currentTimeMillis()
        var currentBody = StringBuilder()

        fun flushMessage() {
            val body = currentBody.toString().trim()
            if (body.isNotBlank()) {
                val isMedia = body.contains("<Media omitted>", ignoreCase = true) ||
                              body.contains("(file attached)", ignoreCase = true) ||
                              body.contains("<attached:", ignoreCase = true)

                var attachmentName: String? = null
                if (isMedia) {
                    val fileMatch = Regex("""([a-zA-Z0-9_\-]+\.(?:jpg|jpeg|png|webp|mp4|mp3|pdf|docx|txt))""").find(body)
                    attachmentName = fileMatch?.value
                }

                val urlMatch = URL_REGEX.find(body)
                val url = urlMatch?.value

                val kind = when {
                    url != null -> ItemKind.LINK
                    isMedia -> {
                        val ext = attachmentName?.substringAfterLast('.', "")?.lowercase() ?: ""
                        when (ext) {
                            "jpg", "jpeg", "png" -> ItemKind.PHOTO
                            "webp" -> ItemKind.STICKER
                            "mp4", "mov" -> ItemKind.VIDEO
                            "mp3", "opus", "m4a", "wav" -> ItemKind.AUDIO
                            "pdf", "doc", "docx" -> ItemKind.DOCUMENT
                            else -> ItemKind.FILE
                        }
                    }
                    body.contains("grade", ignoreCase = true) || body.contains("exam", ignoreCase = true) -> ItemKind.TEXT
                    else -> ItemKind.TEXT
                }

                messages.add(
                    ParsedWhatsAppMessage(
                        rawText = body,
                        sender = currentSender,
                        dateMillis = currentDateMillis,
                        isMediaAttachment = isMedia,
                        attachmentFileName = attachmentName,
                        detectedKind = kind,
                        extractedUrl = url
                    )
                )
            }
            currentBody = StringBuilder()
        }

        // WhatsApp line regex: e.g. "13/10/2026, 14:32 - Ryan: text" or "[13/10/26, 2:32 PM] Ryan: text"
        val headerRegex = Regex("""^\[?(\d{1,4}[/\-.]\d{1,2}[/\-.]\d{2,4},?\s+\d{1,2}:\d{2}(?::\d{2})?(?:\s+[AP]M)?)\]?\s*[-:]?\s*([^:]+)?:\s*(.*)$""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val match = headerRegex.find(line)
            if (match != null) {
                flushMessage()
                val dateStr = match.groupValues[1]
                val senderStr = match.groupValues[2].trim()
                val messageText = match.groupValues[3]

                currentSender = senderStr.ifBlank { null }
                currentDateMillis = parseDate(dateStr)
                currentBody.append(messageText)
            } else {
                // Continuation line
                if (currentBody.isNotEmpty()) {
                    currentBody.append("\n").append(line)
                } else {
                    currentBody.append(line)
                }
            }
        }
        flushMessage()

        val textNotes = messages.count { it.detectedKind == ItemKind.TEXT }
        val links = messages.count { it.detectedKind == ItemKind.LINK }
        val media = messages.count { it.isMediaAttachment }

        return MigrationResult(
            totalMessages = messages.size,
            textNotes = textNotes,
            links = links,
            mediaPlaceholders = media,
            parsedItems = messages
        )
    }

    private fun parseDate(dateStr: String): Long {
        val cleanStr = dateStr.replace("[", "").replace("]", "").trim()
        for (format in DATE_PATTERNS) {
            try {
                val parsed = format.parse(cleanStr)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }
}
