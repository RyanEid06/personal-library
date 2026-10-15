package com.personallibrary.core.migration

import com.personallibrary.core.model.ItemKind
import com.personallibrary.core.storage.ContentClassifier
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class DateOrder {
    DAY_FIRST,   // DD/MM/YYYY
    MONTH_FIRST  // MM/DD/YYYY
}

data class ParsedMessage(
    val importKey: String,
    val rawText: String,
    val sender: String?,
    val rawDateText: String,
    val parsedTimestamp: Long?,
    val isDateAmbiguous: Boolean,
    val isSystemMessage: Boolean,
    val attachmentFileName: String?,
    val isMediaOmitted: Boolean,
    val kind: ItemKind,
    val extractedUrl: String?
)

data class WhatsAppParseResult(
    val totalMessages: Int,
    val validDateCount: Int,
    val ambiguousDateCount: Int,
    val unparseableDateCount: Int,
    val mediaReferencesCount: Int,
    val systemMessageCount: Int,
    val messages: List<ParsedMessage>
)

object WhatsAppExportParser {

    private val URL_REGEX = "https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+".toRegex(RegexOption.IGNORE_CASE)

    // Regex for WhatsApp lines:
    // Pattern 1: [13/10/2026, 14:32:10] Sender: Message
    // Pattern 2: 13/10/2026, 14:32 - Sender: Message
    // Pattern 3: 13/10/26, 2:32 PM - Sender: Message
    // Pattern 4: 13/10/2026, 14:32 - System message (no colon after sender)
    private val HEADER_BRACKET_REGEX = Regex("""^\[(\d{1,4}[/\-.]\d{1,2}[/\-.]\d{2,4}),?\s+(\d{1,2}:\d{2}(?::\d{2})?(?:\s+[AP]M)?)\]\s*(?:([^:]+):)?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val HEADER_DASH_REGEX = Regex("""^(\d{1,4}[/\-.]\d{1,2}[/\-.]\d{2,4}),?\s+(\d{1,2}:\d{2}(?::\d{2})?(?:\s+[AP]M)?)\s*-\s*(?:([^:]+):)?\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun parse(chatText: String, dateOrder: DateOrder? = null, timeZone: TimeZone = TimeZone.getTimeZone("UTC")): WhatsAppParseResult {
        val lines = chatText.lines()
        val parsedMessages = mutableListOf<ParsedMessage>()

        var currentRawDate = ""
        var currentSender: String? = null
        var isCurrentSystem = false
        var currentBody = StringBuilder()

        fun flushMessage() {
            val body = currentBody.toString().trim()
            if (body.isNotEmpty() || currentRawDate.isNotEmpty()) {
                val isOmitted = body.contains("<Media omitted>", ignoreCase = true)
                val isAttached = body.contains("(file attached)", ignoreCase = true) || body.contains("<attached:", ignoreCase = true)
                val isMedia = isOmitted || isAttached

                var attachmentFileName: String? = null
                if (isMedia) {
                    val fileMatch = Regex("""([a-zA-Z0-9_\-.]+\.(?:jpg|jpeg|png|webp|mp4|mov|mp3|opus|m4a|wav|pdf|docx|txt|zip))""", RegexOption.IGNORE_CASE).find(body)
                    attachmentFileName = fileMatch?.value
                }

                val urlMatch = URL_REGEX.find(body)
                val url = urlMatch?.value

                val kind = when {
                    url != null -> ItemKind.LINK
                    isMedia -> ContentClassifier.classify(null, attachmentFileName)
                    else -> ItemKind.TEXT
                }

                val (timestamp, isAmbiguous) = parseDateSafely(currentRawDate, dateOrder, timeZone)

                // Deterministic import key
                val keyInput = "${currentSender ?: ""}_${currentRawDate}_$body"
                val importKey = hashString(keyInput)

                parsedMessages.add(
                    ParsedMessage(
                        importKey = importKey,
                        rawText = body,
                        sender = currentSender,
                        rawDateText = currentRawDate,
                        parsedTimestamp = timestamp,
                        isDateAmbiguous = isAmbiguous,
                        isSystemMessage = isCurrentSystem,
                        attachmentFileName = attachmentFileName,
                        isMediaOmitted = isOmitted,
                        kind = kind,
                        extractedUrl = url
                    )
                )
            }
            currentBody = StringBuilder()
            currentRawDate = ""
            currentSender = null
            isCurrentSystem = false
        }

        for (line in lines) {
            val bracketMatch = HEADER_BRACKET_REGEX.find(line)
            val dashMatch = if (bracketMatch == null) HEADER_DASH_REGEX.find(line) else null
            val match = bracketMatch ?: dashMatch

            if (match != null) {
                flushMessage()
                val datePart = match.groupValues[1].trim()
                val timePart = match.groupValues[2].trim()
                currentRawDate = "$datePart $timePart"

                val rawSender = match.groupValues[3].trim()
                val messageText = match.groupValues[4]

                if (rawSender.isEmpty() || rawSender.contains("Messages and calls are end-to-end encrypted") || rawSender.contains("created group")) {
                    isCurrentSystem = true
                    currentSender = null
                    currentBody.append(if (rawSender.isEmpty()) messageText else "$rawSender: $messageText")
                } else {
                    isCurrentSystem = false
                    currentSender = rawSender
                    currentBody.append(messageText)
                }
            } else {
                if (currentBody.isNotEmpty()) {
                    currentBody.append("\n").append(line)
                } else if (line.isNotBlank()) {
                    currentBody.append(line)
                }
            }
        }
        flushMessage()

        val validCount = parsedMessages.count { it.parsedTimestamp != null && !it.isDateAmbiguous }
        val ambiguousCount = parsedMessages.count { it.isDateAmbiguous }
        val unparseableCount = parsedMessages.count { it.parsedTimestamp == null && !it.isDateAmbiguous }
        val mediaCount = parsedMessages.count { it.attachmentFileName != null || it.isMediaOmitted }
        val systemCount = parsedMessages.count { it.isSystemMessage }

        return WhatsAppParseResult(
            totalMessages = parsedMessages.size,
            validDateCount = validCount,
            ambiguousDateCount = ambiguousCount,
            unparseableDateCount = unparseableCount,
            mediaReferencesCount = mediaCount,
            systemMessageCount = systemCount,
            messages = parsedMessages
        )
    }

    private fun parseDateSafely(
        rawDate: String,
        specifiedOrder: DateOrder?,
        timeZone: TimeZone
    ): Pair<Long?, Boolean> {
        if (rawDate.isBlank()) return Pair(null, false)

        val clean = rawDate.replace("[", "").replace("]", "").replace(",", "").trim()
        val parts = clean.split("\\s+".toRegex())
        if (parts.isEmpty()) return Pair(null, false)

        val datePart = parts[0]
        val timePart = clean.substringAfter(datePart).trim()

        val dateNumbers = datePart.split("[/\\-.]".toRegex()).mapNotNull { it.toIntOrNull() }
        if (dateNumbers.size != 3) return Pair(null, false)

        val (first, second, third) = dateNumbers

        // Year detection (third number or first number)
        val year: Int
        val num1: Int
        val num2: Int

        if (third >= 100) {
            year = third
            num1 = first
            num2 = second
        } else if (third in 20..99) {
            year = 2000 + third
            num1 = first
            num2 = second
        } else if (first >= 1000) {
            year = first
            num1 = second
            num2 = third
        } else {
            return Pair(null, false)
        }

        val month: Int
        val day: Int

        if (num1 <= 12 && num2 <= 12) {
            if (num1 == num2) {
                // Not ambiguous: day and month are identical (e.g. 10/10/2026)
                day = num1
                month = num2
            } else if (specifiedOrder == null) {
                // NEVER GUESS ambiguous dates!
                return Pair(null, true)
            } else if (specifiedOrder == DateOrder.DAY_FIRST) {
                day = num1
                month = num2
            } else {
                month = num1
                day = num2
            }
        } else if (num1 > 12 && num2 <= 12) {
            day = num1
            month = num2
        } else if (num2 > 12 && num1 <= 12) {
            month = num1
            day = num2
        } else {
            return Pair(null, false)
        }

        // Parse time part
        val timeFormatted = parseTimePart(timePart) ?: return Pair(null, false)
        val isoString = "%04d-%02d-%02d %s".format(year, month, day, timeFormatted)

        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                this.timeZone = timeZone
                isLenient = false
            }
            val date = format.parse(isoString)
            Pair(date?.time, false)
        } catch (_: Exception) {
            Pair(null, false)
        }
    }

    private fun parseTimePart(timePart: String): String? {
        if (timePart.isBlank()) return "00:00:00"
        val upper = timePart.uppercase().trim()
        val isPm = upper.contains("PM")
        val isAm = upper.contains("AM")
        val cleanTime = upper.replace("AM", "").replace("PM", "").trim()
        val timeTokens = cleanTime.split(":")
        if (timeTokens.size < 2) return null

        var hour = timeTokens[0].trim().toIntOrNull() ?: return null
        val minute = timeTokens[1].trim().toIntOrNull() ?: return null
        val second = if (timeTokens.size > 2) timeTokens[2].trim().toIntOrNull() ?: 0 else 0

        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0

        return "%02d:%02d:%02d".format(hour, minute, second)
    }

    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
