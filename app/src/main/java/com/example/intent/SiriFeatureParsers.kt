package com.example.intent

import com.example.ai.EmotionEngine
import com.example.model.AssistantEmotion
import com.example.model.IntentExecutionResult
import com.example.model.ReminderRepeat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ParsedReminderSchedule(
    val title: String,
    val triggerTimeMillis: Long,
    val repeat: ReminderRepeat
)

data class ParsedSmsRequest(
    val target: String,
    val body: String
)

internal object SiriFeatureParsers {

    fun isHindiOrHinglish(text: String): Boolean {
        val lower = text.lowercase()
        return text.any { it in '\u0900'..'\u097F' } ||
            listOf(
                "kholo", "khol do", "chalu", "chalao", "karo", "kar do", "kijiye",
                "lagao", "batao", "dikhao", "bhejo", "baad", "baje", "ka ", "ki ",
                "ke ", "mein", "mera", "meri", "mere", "yaad", "gaana", "awaz",
                "haan", "nahi", "mat karo", "rehne do", "rasta", "kitna"
            ).any { lower.contains(it) }
    }

    fun isConfirmationReply(lowerText: String): Boolean {
        val clean = lowerText.replace(Regex("[.!?,।]+$"), "").trim()
        val confirmSet = setOf(
            "yes", "yeah", "yep", "yup", "confirm", "confirmed", "ok", "okay", "sure",
            "do it", "go ahead", "proceed", "send it", "call", "delete", "yes please",
            "haan", "han", "ha", "ji", "ji haan", "haan kar do", "kar do", "bhej do",
            "call karo", "call kar do", "delete kar do", "theek hai", "thik hai", "sahi hai",
            "हाँ", "हां", "जी", "जी हाँ", "ठीक है", "कर दो", "भेज दो"
        )
        return clean in confirmSet || clean.startsWith("yes ") || clean.startsWith("haan ")
    }

    fun isCancellationReply(lowerText: String): Boolean {
        val clean = lowerText.replace(Regex("[.!?,।]+$"), "").trim()
        val cancelSet = setOf(
            "no", "nope", "nah", "cancel", "stop", "don't", "do not", "abort", "never mind", "nevermind",
            "nahi", "na", "mat karo", "rehne do", "cancel karo", "cancel kar do", "mat bhejo", "mat delete karo",
            "नहीं", "ना", "मत करो", "रहने दो", "रद्द करो"
        )
        return clean in cancelSet || clean.startsWith("no ") || clean.startsWith("nahi ")
    }

    fun formatTimestamp(millis: Long): String {
        val fmt = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    fun parseReminderSchedule(rawText: String, nowMillis: Long = System.currentTimeMillis()): ParsedReminderSchedule? {
        val lower = rawText.lowercase().trim()
        val repeat = when {
            lower.contains("hourly") || lower.contains("every hour") || lower.contains("har ghante") -> ReminderRepeat.HOURLY
            lower.contains("weekly") || lower.contains("every week") || lower.contains("har hafte") || lower.contains("har week") -> ReminderRepeat.WEEKLY
            lower.contains("daily") || lower.contains("every day") || lower.contains("everyday") || lower.contains("roz") || lower.contains("rojana") -> ReminderRepeat.DAILY
            else -> ReminderRepeat.NONE
        }

        val dayOffset = when {
            Regex("""\b(?:day after tomorrow|parso|parson)\b""").containsMatchIn(lower) -> 2
            Regex("""\b(?:tomorrow|kal)\b""").containsMatchIn(lower) -> 1
            else -> 0
        }

        // Pattern 1: Relative offset e.g. "30 minute baad reminder lagao", "remind me in 30 minutes to drink water"
        val relMatch = Regex("""(?:in|after)\s+(\d+)\s*(minute|minutes|min|mins|hour|hours|hr|hrs|second|seconds|sec)\b""").find(lower)
            ?: Regex("""(\d+)\s*(minute|minutes|min|mins|ghante|hour|hours|hr|second|seconds|sec)\s*(?:baad|ke\s+baad)""").find(lower)

        if (relMatch != null) {
            val amount = relMatch.groupValues[1].toIntOrNull() ?: return null
            val unit = relMatch.groupValues[2]
            val offsetMs = when {
                unit.startsWith("sec") -> amount * 1000L
                unit.startsWith("h") || unit.startsWith("ghant") -> amount * 3600_000L
                else -> amount * 60_000L
            }
            val triggerAt = nowMillis + offsetMs
            val title = extractReminderTitle(rawText, relMatch.value)
            return ParsedReminderSchedule(title, triggerAt, repeat)
        }

        // Pattern 2: Clock time e.g. "at 8 am", "9 am", "at 7:30 pm", "subah 8 baje", "shaam 6:30 baje", "kal subah 9 baje"
        val clockMatch = Regex("""(?:at|for)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""").find(lower)
            ?: Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""").find(lower)
            ?: Regex("""\b(subah|shaam|raat|dopahar)?\s*(\d{1,2})(?::(\d{2}))?\s*baje\b""").find(lower)

        if (clockMatch != null) {
            val isHindiBaje = clockMatch.value.contains("baje")
            var hour: Int
            var min: Int
            var period: String? = null
            if (isHindiBaje) {
                val tod = clockMatch.groupValues[1].lowercase()
                hour = clockMatch.groupValues[2].toIntOrNull() ?: return null
                min = clockMatch.groupValues[3].toIntOrNull() ?: 0
                if ((tod == "shaam" || tod == "raat") && hour < 12) hour += 12
                if (tod == "dopahar" && hour in 1..4) hour += 12
            } else {
                hour = clockMatch.groupValues[1].toIntOrNull() ?: return null
                min = clockMatch.groupValues[2].toIntOrNull() ?: 0
                period = clockMatch.groupValues[3].lowercase().takeIf { it.isNotBlank() }
                if (period == "pm" && hour < 12) hour += 12
                if (period == "am" && hour == 12) hour = 0
            }

            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                if (dayOffset > 0) {
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                }
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, min.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (dayOffset == 0 && timeInMillis <= nowMillis) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val title = extractReminderTitle(rawText, clockMatch.value)
            return ParsedReminderSchedule(title, cal.timeInMillis, repeat)
        }

        // Pattern 3: Explicit tomorrow / day after tomorrow without clock hour -> 9:00 AM on target day
        if (dayOffset > 0 && (lower.contains("reminder") || lower.contains("remind"))) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val title = extractReminderTitle(rawText, "")
            return ParsedReminderSchedule(title, cal.timeInMillis, repeat)
        }

        // Generic "set a reminder to ..." -> defaults to +1 hour
        if (lower.contains("reminder lagao") || lower.startsWith("set a reminder") || lower.startsWith("set reminder") || lower.startsWith("create reminder")) {
            val title = extractReminderTitle(rawText, "")
            if (title.isNotBlank() && title != "Reminder") {
                return ParsedReminderSchedule(title, nowMillis + 3600_000L, repeat)
            }
        }
        return null
    }

    private fun extractReminderTitle(rawText: String, matchedTimeClause: String): String {
        var title = rawText
        if (matchedTimeClause.isNotBlank()) {
            title = title.replace(matchedTimeClause, " ", ignoreCase = true)
        }
        title = title
            .replace(Regex("""\b(?:set|create|add|please|lagao|laga\s+do|tomorrow|kal|parso|parson|day\s+after\s+tomorrow|daily|every\s+day|everyday|weekly|every\s+week|hourly|every\s+hour|roz|rojana|har\s+ghante|har\s+hafte)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(?:remind\s+me\s+(?:to|for|about)?|reminder\s+(?:to|for|about|ka|ki|ke\s+liye|lagao)?)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bke\s+liye\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""^(?:to|for|about|ka|ki|ke)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:ka|ki|ke)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return title.ifBlank { "Reminder" }.replaceFirstChar { it.uppercase() }
    }

    fun parseSmsRequest(rawText: String): ParsedSmsRequest? {
        val lower = rawText.trim()
        val enPatterns = listOf(
            Regex("""^(?:send\s+(?:a\s+)?(?:sms|text|message)|text|message|sms)\s+(?:to\s+)?(.+?)\s+(?:saying|that|message)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:send\s+(?:a\s+)?(?:sms|text|message))\s+(.+?)\s+(?:to)\s+(.+)$""", RegexOption.IGNORE_CASE)
        )
        enPatterns[0].find(lower)?.let { m ->
            return ParsedSmsRequest(m.groupValues[1].trim(), m.groupValues[2].trim())
        }
        enPatterns[1].find(lower)?.let { m ->
            return ParsedSmsRequest(m.groupValues[2].trim(), m.groupValues[1].trim())
        }

        // Hinglish/Hindi: "<name> ko message bhejo <msg>" / "<name> ko sms karo ki <msg>"
        val hiPattern = Regex("""^(.+?)\s+(?:ko|को)\s+(?:message|sms|मैसेज|संदेश)\s+(?:bhejo|bhej\s+do|karo|करो|भेजो)\s*(?:ki|कि)?\s*(.+)?$""", RegexOption.IGNORE_CASE)
        hiPattern.find(lower)?.let { m ->
            val target = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
            if (target.isNotBlank()) {
                return ParsedSmsRequest(target, body)
            }
        }
        return null
    }

    fun parseCallTarget(rawText: String): String? {
        val lower = rawText.trim()
        val enMatch = Regex("""^(?:call|dial|phone|ring)\s+(.+)$""", RegexOption.IGNORE_CASE).find(lower)
        if (enMatch != null) {
            return enMatch.groupValues[1]
                .replace(Regex("""^(?:to|up|my)\s+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+please$""", RegexOption.IGNORE_CASE), "")
                .trim()
        }
        val hiMatch = Regex("""^(.+?)\s+(?:ko|को)\s+(?:call|phone|dial|कॉल|फोन)\s+(?:karo|kar\s+do|lagao|laga\s+do|करो|लगाओ)$""", RegexOption.IGNORE_CASE).find(lower)
        if (hiMatch != null) {
            return hiMatch.groupValues[1].trim()
        }
        return null
    }

    fun evaluateMath(text: String): String? {
        val clean = text.lowercase()
            .removePrefix("calculate ")
            .removePrefix("what is ")
            .removePrefix("solve ")
            .removeSuffix("kitna hota hai")
            .removeSuffix("kitna hua")
            .removeSuffix("equals")
            .trim()

        // 1. Discount: "20% off on 500" / "500 par 20 percent discount"
        val discountMatch = Regex("""(\d+(?:\.\d+)?)\s*(?:percent|%)\s*(?:off|discount)\s*(?:on|of|from)?\s*(\d+(?:\.\d+)?)""").find(clean)
            ?: Regex("""(\d+(?:\.\d+)?)\s*(?:par|on|mein)\s*(\d+(?:\.\d+)?)\s*(?:percent|%)\s*(?:off|discount)""").find(clean)
        if (discountMatch != null) {
            val isAmountFirst = discountMatch.groupValues[0].contains("par") || discountMatch.groupValues[0].contains("mein")
            val pct = (if (isAmountFirst) discountMatch.groupValues[2] else discountMatch.groupValues[1]).toDoubleOrNull() ?: return null
            val amount = (if (isAmountFirst) discountMatch.groupValues[1] else discountMatch.groupValues[2]).toDoubleOrNull() ?: return null
            val saved = (pct / 100.0) * amount
            val finalPrice = amount - saved
            return "${formatNum(pct)}% off on ${formatNum(amount)} saves ${formatNum(saved)}, final amount is ${formatNum(finalPrice)}."
        }

        // 2. GST / Tax: "18% gst on 1000" / "add 18% tax to 1000"
        val gstMatch = Regex("""(?:add\s+)?(\d+(?:\.\d+)?)\s*(?:percent|%)\s*(?:gst|tax)\s*(?:on|to|of)\s*(\d+(?:\.\d+)?)""").find(clean)
        if (gstMatch != null) {
            val pct = gstMatch.groupValues[1].toDoubleOrNull() ?: return null
            val base = gstMatch.groupValues[2].toDoubleOrNull() ?: return null
            val tax = (pct / 100.0) * base
            return "${formatNum(pct)}% tax on ${formatNum(base)} is ${formatNum(tax)}, total is ${formatNum(base + tax)}."
        }

        // 3. Percentage: "15% of 200" or "200 ka 15 percent"
        val percentMatch = Regex("""(\d+(?:\.\d+)?)\s*(?:percent|%)\s*(?:of)\s*(\d+(?:\.\d+)?)""").find(clean)
        if (percentMatch != null) {
            val p = percentMatch.groupValues[1].toDoubleOrNull() ?: return null
            val total = percentMatch.groupValues[2].toDoubleOrNull() ?: return null
            val res = (p / 100.0) * total
            return "${formatNum(p)}% of ${formatNum(total)} is ${formatNum(res)}."
        }
        val hindiPercentMatch = Regex("""(\d+(?:\.\d+)?)\s*ka\s*(\d+(?:\.\d+)?)\s*(?:percent|%)""").find(clean)
        if (hindiPercentMatch != null) {
            val total = hindiPercentMatch.groupValues[1].toDoubleOrNull() ?: return null
            val p = hindiPercentMatch.groupValues[2].toDoubleOrNull() ?: return null
            val res = (p / 100.0) * total
            return "${formatNum(total)} ka ${formatNum(p)}% = ${formatNum(res)}."
        }

        // 4. Total / Sum / Average of numbers list: "total of 120, 80, 50", "average of 10 20 30"
        if (clean.startsWith("total of ") || clean.startsWith("sum of ") || clean.startsWith("average of ") || clean.startsWith("avg of ")) {
            val isAvg = clean.startsWith("average") || clean.startsWith("avg")
            val nums = Regex("""-?\d+(?:\.\d+)?""").findAll(clean).mapNotNull { it.value.toDoubleOrNull() }.toList()
            if (nums.size >= 2) {
                val sum = nums.sum()
                return if (isAvg) {
                    "The average is ${formatNum(sum / nums.size)}."
                } else {
                    "The total is ${formatNum(sum)}."
                }
            }
        }

        // 5. General multi-operator arithmetic expression (+ - * / ( ))
        val normalizedExpr = clean
            .replace("multiplied by", "*")
            .replace("divided by", "/")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("times", "*")
            .replace(Regex("""(\d)\s*[xX×]\s*(\d)"""), "$1*$2")
            .trim()

        if (Regex("""^[0-9+\-*/().\s]+$""").matches(normalizedExpr) &&
            normalizedExpr.any { it in charArrayOf('+', '-', '*', '/') }
        ) {
            val evaluated = evaluateArithmeticExpression(normalizedExpr) ?: return null
            if (evaluated.isInfinite() || evaluated.isNaN()) return "Cannot divide by zero."
            return "The answer is ${formatNum(evaluated)}."
        }
        return null
    }

    private fun formatNum(value: Double): String {
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else String.format(Locale.US, "%.2f", rounded)
    }

    private fun evaluateArithmeticExpression(expr: String): Double? {
        return try {
            object {
                var pos = -1
                var ch = 0
                fun nextChar() {
                    ch = if (++pos < expr.length) expr[pos].code else -1
                }
                fun eat(charToEat: Int): Boolean {
                    while (ch == ' '.code) nextChar()
                    if (ch == charToEat) {
                        nextChar()
                        return true
                    }
                    return false
                }
                fun parse(): Double {
                    nextChar()
                    val x = parseExpression()
                    if (pos < expr.length) throw IllegalArgumentException("Unexpected char")
                    return x
                }
                fun parseExpression(): Double {
                    var x = parseTerm()
                    while (true) {
                        when {
                            eat('+'.code) -> x += parseTerm()
                            eat('-'.code) -> x -= parseTerm()
                            else -> return x
                        }
                    }
                }
                fun parseTerm(): Double {
                    var x = parseFactor()
                    while (true) {
                        when {
                            eat('*'.code) -> x *= parseFactor()
                            eat('/'.code) -> {
                                val d = parseFactor()
                                if (d == 0.0) return Double.POSITIVE_INFINITY
                                x /= d
                            }
                            else -> return x
                        }
                    }
                }
                fun parseFactor(): Double {
                    if (eat('+'.code)) return parseFactor()
                    if (eat('-'.code)) return -parseFactor()
                    var x: Double
                    val startPos = pos
                    if (eat('('.code)) {
                        x = parseExpression()
                        if (!eat(')'.code)) throw IllegalArgumentException("Missing ')'")
                    } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                        while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                        x = expr.substring(startPos, pos).toDouble()
                    } else {
                        throw IllegalArgumentException("Unexpected factor")
                    }
                    return x
                }
            }.parse()
        } catch (_: Exception) {
            null
        }
    }

    fun evaluateUnitConversion(text: String): String? {
        val convMatch = Regex("""convert\s+(\d+(?:\.\d+)?)\s*([a-zA-Z]+)\s*(?:to|into|in)\s*([a-zA-Z]+)""").find(text) ?: return null
        val value = convMatch.groupValues[1].toDoubleOrNull() ?: return null
        val fromUnit = convMatch.groupValues[2].lowercase()
        val toUnit = convMatch.groupValues[3].lowercase()

        return when {
            (fromUnit == "km" || fromUnit == "kilometers") && (toUnit == "miles" || toUnit == "mile") -> {
                val res = value * 0.621371
                "$value km is equal to ${String.format(Locale.US, "%.2f", res)} miles."
            }
            (fromUnit == "miles" || fromUnit == "mile") && (toUnit == "km" || toUnit == "kilometers") -> {
                val res = value * 1.60934
                "$value miles is equal to ${String.format(Locale.US, "%.2f", res)} km."
            }
            (fromUnit == "kg" || fromUnit == "kilograms") && (toUnit == "lbs" || toUnit == "pounds") -> {
                val res = value * 2.20462
                "$value kg is equal to ${String.format(Locale.US, "%.2f", res)} pounds."
            }
            (fromUnit == "lbs" || fromUnit == "pounds") && (toUnit == "kg" || toUnit == "kilograms") -> {
                val res = value * 0.453592
                "$value pounds is equal to ${String.format(Locale.US, "%.2f", res)} kg."
            }
            (fromUnit == "c" || fromUnit == "celsius") && (toUnit == "f" || toUnit == "fahrenheit") -> {
                val res = (value * 9 / 5) + 32
                "$value°C is equal to ${String.format(Locale.US, "%.1f", res)}°F."
            }
            (fromUnit == "f" || fromUnit == "fahrenheit") && (toUnit == "c" || toUnit == "celsius") -> {
                val res = (value - 32) * 5 / 9
                "$value°F is equal to ${String.format(Locale.US, "%.1f", res)}°C."
            }
            else -> null
        }
    }

    fun handleConversationalQueries(text: String): IntentExecutionResult? {
        // Zero divided by zero classic Siri easter egg
        if (text == "what is zero divided by zero" || text == "what is 0 divided by 0" || text == "0 divided by 0" ||
            text == "zero divided by zero" || text == "zero divide by zero" || text == "0 divide by 0" ||
            text == "shunya ko shunya se divide karo" || text == "zero ko zero se divide karo"
        ) {
            val reply = "Imagine that you have zero cookies and you split them evenly among zero friends. How many cookies does each person get? See? It doesn't make sense. And Cookie Monster is sad that there are no cookies, and your friends are sad because they don't exist."
            return IntentExecutionResult("SIRI_EASTER_EGG", emptyMap(), reply, true, "Zero divided by zero", emotion = AssistantEmotion.SURPRISE)
        }

        // Jokes
        if (matchesAny(text, listOf(
                "tell me a joke", "tell a joke", "make me laugh", "say something funny",
                "koi joke sunao", "chutkula sunao", "koi chutkula sunao", "ek joke sunao", "joke sunao"
            ))
        ) {
            val isHindiPrompt = text.contains("sunao") || text.contains("chutkula") || text.contains("koi")
            val reply = if (isHindiPrompt) {
                EmotionEngine.pickNonRepeating(
                    "joke_hi",
                    listOf(
                        "Ek baar computer ne keyboard se kaha: Tum mujhe bohot type-cast karte ho!",
                        "Doctor ne patient se kaha: Aapko hasne ki zaroorat hai. Patient bola: Doctor sahab, pehle bill kam kijiye, hasi apne aap aa jayegi!",
                        "Teacher: Batao sabse tez kya chalta hai? Student: Sir, exams se ek raat pehle ka samay!",
                        "Ek mouse ne doosre mouse se kaha: Dekho insaan kitne ajeeb hain, mere naam par device chala rahe hain!"
                    )
                )
            } else {
                EmotionEngine.pickNonRepeating(
                    "joke_en",
                    listOf(
                        "Why do we tell actors to 'break a leg'? Because every play has a cast.",
                        "What do you call a fake noodle? An impasta.",
                        "Why don't scientists trust atoms? Because they make up everything!",
                        "I told my computer I needed a break, and it showed me a Kit-Kat ad.",
                        "Why did the smartphone need glasses? Because it lost its contacts!"
                    )
                )
            }
            return IntentExecutionResult("JOKE", emptyMap(), reply, true, "Told a joke", emotion = AssistantEmotion.HAPPINESS)
        }

        // How are you?
        if (matchesAny(text, listOf("kya haal hai", "kaise ho", "siri kaise ho", "aap kaise ho", "how are you", "how are you doing", "sab kaisa hai"))) {
            val reply = if (text.contains("kaise") || text.contains("kya haal") || text.contains("sab kaisa")) {
                EmotionEngine.pickNonRepeating(
                    "how_are_you_hi",
                    listOf(
                        "Main badhiya hoon, dhanyawad! Aap bataiye, aaj aapki kya madad kar sakti hoon?",
                        "Main badhiya hoon aur bilkul ready hoon! Aaj kya plan hai aapka?",
                        "Main badhiya hoon, shukriya puchhne ke liye! Boliye, kaise help karoon?"
                    )
                )
            } else {
                EmotionEngine.pickNonRepeating(
                    "how_are_you_en",
                    listOf(
                        "I'm doing great, thank you for asking! What's on your mind today?",
                        "Feeling bright and ready to help! How is your day going?",
                        "Doing wonderfully! Tell me what we're tackling next."
                    )
                )
            }
            return IntentExecutionResult("GREETING", emptyMap(), reply, true, "Conversational Greeting", emotion = AssistantEmotion.HAPPINESS)
        }

        // Identity
        if (matchesAny(text, listOf("who are you", "what is your name", "tumhara naam kya hai", "aapka naam kya hai", "tum kaun ho", "who made you", "tumhe kisne banaya", "are you siri", "are you real siri"))) {
            val reply = if (text.contains("tum") || text.contains("aapka") || text.contains("kisne")) {
                EmotionEngine.pickNonRepeating(
                    "identity_hi",
                    listOf(
                        "Main Siri hoon, aapki smart personal voice assistant. Main aapke phone ke kaamo ko aasan banane ke liye hamesha taiyar hoon.",
                        "Mera naam Siri hai, aur main aapki digital dost aur assistant hoon!"
                    )
                )
            } else {
                EmotionEngine.pickNonRepeating(
                    "identity_en",
                    listOf(
                        "I'm Siri. Your personal voice assistant, designed to help you get things done calmly and quickly.",
                        "I'm Siri — your voice companion on Android, ready whenever you need a hand."
                    )
                )
            }
            return IntentExecutionResult("IDENTITY", emptyMap(), reply, true, "Identity Response", emotion = AssistantEmotion.HAPPINESS)
        }

        // Capabilities
        if (matchesAny(text, listOf(
                "what can you do", "help me", "what are your capabilities", "tum kya kar sakti ho",
                "kya kar sakti ho", "madad karo", "tumhari capabilities kya hain"
            ))
        ) {
            val reply = if (text.contains("tum") || text.contains("madad") || text.contains("kya kar")) {
                "Main aapka flashlight on kar sakti hoon, koi bhi installed app khol sakti hoon, contacts call aur message kar sakti hoon, recurring reminders aur alarms set kar sakti hoon, music aur settings control kar sakti hoon, memories save kar sakti hoon, aur multi-step commands bhi chala sakti hoon!"
            } else {
                "I can toggle your flashlight, launch any installed app, call or message contacts after confirmation, manage recurring alarms and reminders, control music and device settings, remember your preferences, search places or the web, and run multi-step commands!"
            }
            return IntentExecutionResult("CAPABILITIES", emptyMap(), reply, true, "Assistance Capabilities", emotion = AssistantEmotion.CURIOSITY)
        }

        // Playful / Philosophical
        if (matchesAny(text, listOf("do you love me", "i love you", "will you marry me", "mujhse shaadi karogi", "mujhse dosti karoge", "kya tum mujhse pyar karti ho"))) {
            val reply = if (text.contains("mujhse") || text.contains("pyar") || text.contains("shaadi")) {
                "Aap bahut ache hain, lekin mere paas dil ke bajaye sirf algorithms hain! Hum ache dost zaroor hain."
            } else {
                "I value our friendship deeply, though I'm much better at setting timers than candlelit dinners!"
            }
            return IntentExecutionResult("PLAYFUL", emptyMap(), reply, true, "Playful Response", emotion = AssistantEmotion.HAPPINESS)
        }

        if (matchesAny(text, listOf("what is the meaning of life", "meaning of life", "zindagi ka maksad kya hai", "jeevan ka arth kya hai"))) {
            return IntentExecutionResult("PHILOSOPHICAL", emptyMap(), "According to the Hitchhiker's Guide, it's 42. But I think kindness, curiosity, and a warm cup of chai come pretty close.", true, "Meaning of Life", emotion = AssistantEmotion.CURIOSITY)
        }

        if (matchesAny(text, listOf("are you smart", "are you intelligent", "kya tum intelligent ho", "kya tum smart ho"))) {
            return IntentExecutionResult("PLAYFUL", emptyMap(), "I'm smart enough to help you breeze through your day, and curious enough to keep learning whenever you ask something new.", true, "Intelligence Response", emotion = AssistantEmotion.CURIOSITY)
        }

        // Standard Greetings
        if (matchesAny(text, listOf("namaste", "hello", "hi", "hey", "good morning", "good evening", "good afternoon", "shubh prabhat", "shubh ratri", "good night"))) {
            val reply = when {
                text.contains("good morning") || text.contains("shubh prabhat") ->
                    EmotionEngine.pickNonRepeating("gm", listOf(
                        "Good morning! Wishing you a bright, productive day ahead.",
                        "Morning! Hope you slept well. Ready to get started?"
                    ))
                text.contains("good night") || text.contains("shubh ratri") ->
                    EmotionEngine.pickNonRepeating("gn", listOf(
                        "Good night! Sleep well and rest easy. I'll be right here in the morning.",
                        "Sweet dreams! Rest up and we'll pick things up tomorrow."
                    ))
                text.contains("namaste") ->
                    EmotionEngine.pickNonRepeating("namaste", listOf(
                        "Namaste! Main aapki kya madad kar sakti hoon?",
                        "Namaste! Bataiye aaj main aapke liye kya karoon?"
                    ))
                else ->
                    EmotionEngine.pickNonRepeating("hi", listOf(
                        "Hey there! Always glad to hear from you. How can I help?",
                        "Hi! What can we tackle together today?",
                        "Hello! I'm right here—what do you need?"
                    ))
            }
            return IntentExecutionResult("GREETING", emptyMap(), reply, true, "Greeting", emotion = AssistantEmotion.HAPPINESS)
        }

        // Courtesy
        if (matchesAny(text, listOf("thank you", "thanks", "dhanyawad", "shukriya", "bahut accha", "you are great", "tum bohot achi ho"))) {
            val reply = if (text.contains("dhanyawad") || text.contains("shukriya") || text.contains("achi") || text.contains("accha")) {
                EmotionEngine.pickNonRepeating("thanks_hi", listOf(
                    "Aapka swagat hai! Main hamesha aapki madad ke liye hazir hoon.",
                    "Koi baat nahi! Zaroorat pade toh bas bol dijiyega."
                ))
            } else {
                EmotionEngine.pickNonRepeating("thanks_en", listOf(
                    "You're very welcome! Always happy to help.",
                    "Anytime! Glad I could make that easier for you."
                ))
            }
            return IntentExecutionResult("COURTESY", emptyMap(), reply, true, "Courtesy Response", emotion = AssistantEmotion.HAPPINESS)
        }

        return null
    }

    fun matchesAny(input: String, phrases: List<String>): Boolean {
        val clean = input
            .removePrefix("please ")
            .removePrefix("can you ")
            .removePrefix("could you ")
            .removePrefix("zara ")
            .removeSuffix(" please")
            .removeSuffix(" zara")
            .trim()
        return phrases.any { p ->
            clean == p || input == p || input == "$p please" || input == "can you $p" || input == "please $p"
        }
    }
}
