package com.example.ai

import com.example.model.AssistantEmotion
import com.example.model.LanguageOption
import com.example.model.MemoryItem
import com.example.model.NoteItem
import com.example.model.ReminderItem
import com.example.model.ReminderRepeat
import com.example.model.TaskItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OfficeAssistantResponse(
    val intentName: String,
    val text: String,
    val summary: String,
    val emotion: AssistantEmotion = AssistantEmotion.SERIOUSNESS,
    val isNoteSaveable: Boolean = false,
    val suggestedNoteTitle: String = "Office Draft"
)

/**
 * Handles complete Offline Assistant intelligence and Office Mode productivity/study tools
 * using deterministic, honest, zero-network logic alongside structured templates.
 */
object OfflineAssistantEngine {

    private data class QaEntry(
        val keywords: List<String>,
        val answerEn: String,
        val answerHi: String
    )

    private val offlineKnowledgeBase = listOf(
        QaEntry(
            keywords = listOf("capital of india", "india capital", "bharat ki rajdhani"),
            answerEn = "The capital of India is New Delhi.",
            answerHi = "Bharat ki rajdhani New Delhi hai."
        ),
        QaEntry(
            keywords = listOf("prime minister of india", "pm of india", "bharat ke pradhanmantri"),
            answerEn = "India is a parliamentary republic led by an elected Prime Minister. Check live news online for the current officeholder.",
            answerHi = "Bharat ek sansadiya loktantra hai. Current update ke liye online search check kar sakte hain."
        ),
        QaEntry(
            keywords = listOf("speed of light", "light speed", "prakash ki gati"),
            answerEn = "The speed of light in a vacuum is approximately 299,792 kilometers per second (about 300,000 km/s).",
            answerHi = "Vacuum mein prakash ki gati lagbhag 299,792 kilometer per second hoti hai."
        ),
        QaEntry(
            keywords = listOf("boiling point of water", "water boils", "pani kab ubalta hai"),
            answerEn = "Pure water boils at 100 degrees Celsius (212 degrees Fahrenheit) at standard sea-level pressure.",
            answerHi = "Samanya pressure par shuddh paani 100 degree Celsius par ubalta hai."
        ),
        QaEntry(
            keywords = listOf("photosynthesis", "prakash sanshleshan"),
            answerEn = "Photosynthesis is the process where green plants use sunlight, water, and carbon dioxide inside chlorophyll to create glucose and release oxygen.",
            answerHi = "Photosynthesis woh prakriya hai jisme hare paudhe suraj ki roshni, paani aur carbon dioxide ki madad se apna bhojan aur oxygen banate hain."
        ),
        QaEntry(
            keywords = listOf("gravity", "newton's laws", "newton law", "gurutvakarshan"),
            answerEn = "Gravity is the fundamental attractive force between masses. Earth's surface gravity accelerates falling objects at about 9.8 meters per second squared.",
            answerHi = "Gravity do vastuon ke beech aakarshan bal hai. Prithvi par iska acceleration lagbhag 9.8 meter per second squared hota hai."
        ),
        QaEntry(
            keywords = listOf("what is ai", "artificial intelligence", "ai kya hai"),
            answerEn = "Artificial Intelligence (AI) is computer science focused on building systems that learn patterns, understand language, recognize images, and assist with decisions.",
            answerHi = "Artificial Intelligence (AI) computer science ki woh shakh hai jo machines ko seekhne, bhasha samajhne aur samasya suljhane ki kshamta deti hai."
        ),
        QaEntry(
            keywords = listOf("what is api", "api kya hai", "full form of api"),
            answerEn = "API stands for Application Programming Interface. It defines clean rules allowing two software applications to communicate and exchange data securely.",
            answerHi = "API ka matlab Application Programming Interface hai. Yeh do software systems ke beech data exchange karne ka ek niyam-baddh zariya hai."
        ),
        QaEntry(
            keywords = listOf("pomodoro", "study technique", "focus method", "padhai kaise kare"),
            answerEn = "The Pomodoro technique uses 25 minutes of focused study followed by a 5-minute break. After 4 cycles, take a 15 to 20 minute rest.",
            answerHi = "Pomodoro technique mein 25 minute dhyan se padhai aur 5 minute ka break liya jata hai. Isse focus aur retention dono badhte hain."
        ),
        QaEntry(
            keywords = listOf("http vs https", "what is https", "https kya hai"),
            answerEn = "HTTPS adds TLS encryption on top of HTTP so data sent between your device and a website stays private and protected against tampering.",
            answerHi = "HTTPS, HTTP ka surakshit roop hai jo TLS encryption ke zariye aapke aur website ke beech bheje jane wale data ko surakshit rakhta hai."
        ),
        QaEntry(
            keywords = listOf("planets in solar system", "how many planets", "kitne grah hain"),
            answerEn = "There are 8 planets in our solar system: Mercury, Venus, Earth, Mars, Jupiter, Saturn, Uranus, and Neptune.",
            answerHi = "Hamare saurmandal mein 8 grah hain: Mercury, Venus, Earth, Mars, Jupiter, Saturn, Uranus aur Neptune."
        ),
        QaEntry(
            keywords = listOf("human heart", "normal heart rate", "heartbeat"),
            answerEn = "A normal resting adult heart rate ranges from 60 to 100 beats per minute.",
            answerHi = "Ek swasth vayask ka resting heart rate 60 se 100 beats per minute hota hai."
        ),
        QaEntry(
            keywords = listOf("pythagoras", "pythagorean theorem"),
            answerEn = "In a right-angled triangle, the square of the hypotenuse equals the sum of squares of the other two sides: a squared plus b squared equals c squared.",
            answerHi = "Samkon tribhuj mein hypotenuse ka varg baaki dono bhujaon ke vargon ke yog ke barabar hota hai: a² + b² = c²."
        )
    )

    private val bilingualPhrasebook = listOf(
        Triple("good morning", "Good morning", "Suprabhat / Shubh Prabhat (सुप्रभात)"),
        Triple("thank you", "Thank you", "Dhanyawad / Shukriya (धन्यवाद)"),
        Triple("how are you", "How are you?", "Aap kaise hain? (आप कैसे हैं?)"),
        Triple("i am fine", "I am fine", "Main theek hoon (मैं ठीक हूँ)"),
        Triple("sorry", "I am sorry", "Maaf kijiye (माफ़ कीजिए)"),
        Triple("please send the report", "Please send the report", "Kripya report bhej dijiye (कृपया रिपोर्ट भेज दीजिए)"),
        Triple("i am on leave today", "I am on leave today", "Main aaj chhutti par hoon (मैं आज छुट्टी पर हूँ)"),
        Triple("meeting is postponed", "The meeting is postponed", "Meeting aage badha di gayi hai (मीटिंग आगे बढ़ा दी गई है)"),
        Triple("please schedule a meeting", "Please schedule a meeting", "Kripya ek meeting schedule karein (कृपया एक मीटिंग शेड्यूल करें)"),
        Triple("please find attached", "Please find attached", "Kripya attached document dekhein (कृपया संलग्न दस्तावेज़ देखें)"),
        Triple("see you tomorrow", "See you tomorrow", "Kal milte hain (कल मिलते हैं)"),
        Triple("what is your name", "What is your name?", "Aapka naam kya hai? (आपका नाम क्या है?)"),
        Triple("suprabhat", "Good morning", "Suprabhat (सुप्रभात)"),
        Triple("dhanyawad", "Thank you", "Dhanyawad (धन्यवाद)"),
        Triple("aap kaise hain", "How are you?", "Aap kaise hain? (आप कैसे हैं?)"),
        Triple("kal milte hain", "See you tomorrow", "Kal milte hain (कल मिलते हैं)")
    )

    /**
     * Executes Office Mode productivity & study requests (works offline, and also powers
     * local-data-backed actions like note summarization and daily work organization even online).
     */
    fun tryHandleOfficeCommand(
        rawText: String,
        isOnline: Boolean,
        isOfficeMode: Boolean,
        notes: List<NoteItem>,
        tasks: List<TaskItem>,
        reminders: List<ReminderItem>,
        userName: String,
        onAddFlashcard: ((question: String, answer: String, topic: String) -> Unit)? = null
    ): OfficeAssistantResponse? {
        val clean = rawText.trim()
        val lower = clean.lowercase()
        val isHi = isHindiLike(lower)

        // 1. Summarize stored in-app notes (always local-data aware)
        if (lower.contains("summarize my notes") || lower.contains("summary of my notes") ||
            lower.contains("notes summarize") || lower.contains("mere notes summarize") ||
            lower.contains("notes ka summary") || lower.contains("note summarize")
        ) {
            return summarizeLocalNotes(notes, isHi)
        }

        // 2. Daily Work & Schedule Organizer (reads tasks + reminders + work notes)
        if (lower.contains("organize my work") || lower.contains("organize my day") ||
            lower.contains("daily work plan") || lower.contains("work plan") ||
            lower.contains("work schedule") || lower.contains("aaj ka work plan") ||
            lower.contains("kaam organize karo") || lower.contains("plan my day")
        ) {
            return buildDailyWorkPlan(tasks, reminders, notes, userName, isHi)
        }

        // 3. Revision Notes & Flashcards generator from notes (or topic if offline)
        if (lower.contains("revision notes") || lower.contains("flashcards from notes") ||
            lower.contains("create flashcards") || lower.contains("study revision") ||
            lower.contains("revision notes banao")
        ) {
            return generateRevisionAndFlashcards(notes, onAddFlashcard, isHi)
        }

        // 4. Local Translation Helper
        if (lower.startsWith("translate ") || lower.contains("translate to hindi") ||
            lower.contains("translate to english") || lower.contains("hindi mein translate") ||
            lower.contains("english mein translate")
        ) {
            val localTranslation = tryTranslateOffline(clean, isOnline)
            if (localTranslation != null) return localTranslation
        }

        // If online and in Office mode, let Gemini/OpenAI stream bespoke drafts/explanations
        // for open-ended drafting, document explanation, and study questions unless offline.
        if (isOnline && isOfficeMode) {
            // Handle offline-specific prompt triggers only when offline; when online let LLM enrich it.
            val wantsExplicitTextSummary = lower.startsWith("summarize text:") || lower.startsWith("summarize:")
            if (!wantsExplicitTextSummary) {
                return null
            }
        }

        // 5. Offline Office Templates (Email / Leave / Letter / Report / Study Summarize / Explain)
        if (lower.contains("draft") && lower.contains("email") ||
            lower.contains("write an email") || lower.contains("write a professional email") ||
            lower.contains("professional email") || lower.contains("email draft karo") ||
            lower.contains("email likho")
        ) {
            return draftProfessionalEmailOffline(clean, userName, isHi)
        }

        if (lower.contains("leave application") || lower.contains("write an application") ||
            lower.contains("formal letter") || lower.contains("job application") ||
            lower.contains("chutti ki application") || lower.contains("application likho") ||
            lower.contains("letter likho")
        ) {
            return draftApplicationOrLetterOffline(clean, userName, isHi)
        }

        if (lower.contains("report outline") || lower.contains("prepare a report") ||
            lower.contains("report structure") || lower.contains("report banao")
        ) {
            return buildReportOutlineOffline(clean, isHi)
        }

        // 6. Summarize pasted text locally (extractive summary)
        val summarizePrefix = Regex("""^(?:summarize(?:\s+this)?(?:\s+text|\s+document)?|is\s+text\s+ko\s+summarize\s+karo)\s*[:\-]\s*(.+)$""", RegexOption.IGNORE_CASE).find(clean)
        if (summarizePrefix != null) {
            val body = summarizePrefix.groupValues[1].trim()
            return summarizePastedText(body, isHi)
        }

        // 7. Study topic explanation when offline
        val explainMatch = Regex("""^(?:explain|study|what is|samjhao|is topic ko.*samjhao)\s*[:\-]?\s*(.+)$""", RegexOption.IGNORE_CASE).find(clean)
        if (explainMatch != null && !isOnline) {
            val topic = explainMatch.groupValues[1].trim()
            val kb = findKnowledgeMatch(topic.lowercase())
            if (kb != null) {
                return OfficeAssistantResponse(
                    intentName = "OFFICE_STUDY_EXPLAIN",
                    text = if (isHi) kb.answerHi else kb.answerEn,
                    summary = "Offline Study Concept",
                    emotion = AssistantEmotion.CURIOSITY
                )
            }
        }

        return null
    }

    /**
     * Answers general offline queries honestly without making network requests.
     */
    fun answerOfflineQuery(
        prompt: String,
        language: LanguageOption,
        memories: List<MemoryItem>,
        userName: String,
        onDeviceAiSupported: Boolean,
        userPrefsSummary: String = ""
    ): Pair<String, AssistantEmotion> {
        val lower = prompt.lowercase().trim()
        val isHi = language == LanguageOption.HINDI || language == LanguageOption.HINGLISH || isHindiLike(lower)

        // Check device offline AI capability inquiry honestly
        if (lower.contains("offline ai") || lower.contains("on-device") || lower.contains("what can you do offline") ||
            lower.contains("offline mein kya kar")
        ) {
            val hwNote = if (onDeviceAiSupported) {
                "Your device hardware meets baseline specs for on-device engines, though no large local LLM weights are bundled in this APK."
            } else {
                "This device relies on Siri's built-in deterministic offline rule engine and local knowledge base."
            }
            val capabilities = "Offline I can open installed apps, open phone settings, toggle flashlight, check battery, storage & device info, set alarms & local reminders, control media playback, do multi-step math & percentages, answer built-in Q&A, manage notes/tasks/memories, and generate Office email/letter/report templates. $hwNote Cloud Gemini/OpenAI streaming requires an active internet connection."
            return capabilities to AssistantEmotion.CURIOSITY
        }

        // Check saved user preferences offline
        if (lower.contains("my preferences") || lower.contains("saved preferences") || lower.contains("user preferences") ||
            lower.contains("my profile") || lower.contains("meri settings")
        ) {
            val reply = if (userPrefsSummary.isNotBlank()) {
                userPrefsSummary
            } else if (isHi) {
                "Aapka naam $userName save hai."
            } else {
                "Your saved name preference is $userName."
            }
            return reply to AssistantEmotion.CURIOSITY
        }

        // Check user's locally saved memories first
        val matchingMemory = memories.firstOrNull { mem ->
            val hay = "${mem.title} ${mem.content}".lowercase()
            lower.split(Regex("""\s+""")).filter { it.length >= 4 }.any { hay.contains(it) }
        }
        if (matchingMemory != null && (lower.contains("my ") || lower.contains("mera") || lower.contains("meri") || lower.contains("remember"))) {
            return "From your local memory: ${matchingMemory.content}" to AssistantEmotion.CURIOSITY
        }

        // Check built-in offline knowledge base
        findKnowledgeMatch(lower)?.let { kb ->
            val reply = if (isHi) kb.answerHi else kb.answerEn
            return reply to AssistantEmotion.CURIOSITY
        }

        // Personal greeting using stored name preference
        if (lower.contains("my name") || lower.contains("mera naam")) {
            val reply = if (isHi) "Aapka naam $userName save hai." else "Your saved name preference is $userName."
            return reply to AssistantEmotion.HAPPINESS
        }

        // Honest boundary explaining internet is needed for open-ended cloud AI
        val honestReply = if (isHi) {
            "Abhi aap Offline Mode mein hain (ya internet connected nahi hai). Main bina internet ke apps khol sakti hoon, settings, flashlight, battery, storage, device info, alarms, reminders, math calculations, notes/tasks aur Office templates handle kar sakti hoon. Is sawal ka advanced AI jawab dene ke liye internet connection aur Online Mode zaroori hai."
        } else {
            "I'm currently in Offline Mode (or no internet connection is available). Without internet I can still launch apps, control settings & flashlight, check battery/storage/device info, set alarms & reminders, solve math, organize notes/tasks, and generate Office templates—but open-ended cloud AI questions require an internet connection and Online Mode."
        }
        return honestReply to AssistantEmotion.SERIOUSNESS
    }

    private fun summarizeLocalNotes(notes: List<NoteItem>, isHi: Boolean): OfficeAssistantResponse {
        if (notes.isEmpty()) {
            val emptyMsg = if (isHi) {
                "Abhi aapke paas koi saved note nahi hai. Aap 'create note' bolkar naya note add kar sakte hain."
            } else {
                "You don't have any saved notes yet. Say 'create note' followed by your text to save one."
            }
            return OfficeAssistantResponse("OFFICE_SUMMARIZE_NOTES", emptyMsg, "No notes to summarize", AssistantEmotion.SERIOUSNESS)
        }

        val grouped = notes.groupBy { it.category }
        val sb = StringBuilder()
        sb.append(if (isHi) "Aapke ${notes.size} notes ka summary:\n" else "Summary of your ${notes.size} saved notes:\n")
        for ((category, list) in grouped) {
            sb.append("[$category] (${list.size}): ")
            val highlights = list.take(3).joinToString("; ") { note ->
                val snippet = note.content.replace("\n", " ").trim().take(90)
                "${note.title} — $snippet"
            }
            sb.append(highlights).append("\n")
        }
        return OfficeAssistantResponse(
            intentName = "OFFICE_SUMMARIZE_NOTES",
            text = sb.toString().trim(),
            summary = "Summarized ${notes.size} local notes",
            emotion = AssistantEmotion.HAPPINESS
        )
    }

    private fun buildDailyWorkPlan(
        tasks: List<TaskItem>,
        reminders: List<ReminderItem>,
        notes: List<NoteItem>,
        userName: String,
        isHi: Boolean
    ): OfficeAssistantResponse {
        val pendingTasks = tasks.filter { !it.isCompleted }
        val high = pendingTasks.filter { it.priority.equals("High", ignoreCase = true) }
        val normal = pendingTasks.filterNot { it.priority.equals("High", ignoreCase = true) }
        val activeReminders = reminders.filter { it.isEnabled }.take(4)
        val workNotes = notes.filter { it.category.equals("Work", ignoreCase = true) || it.category.equals("Study", ignoreCase = true) }.take(3)

        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        sb.append(if (isHi) "$userName, aapka aaj ka Office & Work Plan:\n" else "Daily Work Plan for $userName:\n")
        if (high.isNotEmpty()) {
            sb.append("Top Priority: ").append(high.joinToString("; ") { "${it.title} (${it.timeLabel})" }).append("\n")
        }
        if (normal.isNotEmpty()) {
            sb.append("Next Tasks: ").append(normal.take(4).joinToString("; ") { "${it.title} (${it.timeLabel})" }).append("\n")
        }
        if (activeReminders.isNotEmpty()) {
            sb.append("Upcoming Reminders: ").append(activeReminders.joinToString("; ") { "${it.title} at ${fmt.format(Date(it.triggerTimeMillis))}" }).append("\n")
        }
        if (workNotes.isNotEmpty()) {
            sb.append("Reference Notes: ").append(workNotes.joinToString("; ") { it.title })
        }
        if (pendingTasks.isEmpty() && activeReminders.isEmpty()) {
            sb.append(if (isHi) "Abhi koi pending task ya reminder nahi hai. Aap naye tasks ya meeting reminders add kar sakte hain." else "Your schedule is clear! Add tasks or meeting reminders anytime.")
        }

        return OfficeAssistantResponse(
            intentName = "OFFICE_WORK_PLAN",
            text = sb.toString().trim(),
            summary = "Organized ${pendingTasks.size} tasks & ${activeReminders.size} reminders",
            emotion = AssistantEmotion.HAPPINESS,
            isNoteSaveable = true,
            suggestedNoteTitle = "Daily Work Plan"
        )
    }

    private fun generateRevisionAndFlashcards(
        notes: List<NoteItem>,
        onAddFlashcard: ((String, String, String) -> Unit)?,
        isHi: Boolean
    ): OfficeAssistantResponse {
        val studyNotes = notes.filter { it.category.equals("Study", ignoreCase = true) }.ifEmpty { notes }
        if (studyNotes.isEmpty()) {
            val msg = if (isHi) {
                "Revision notes banane ke liye pehle kuch Study notes save karein."
            } else {
                "Save at least one note first so I can generate offline revision highlights and flashcards from it."
            }
            return OfficeAssistantResponse("OFFICE_REVISION_NOTES", msg, "No notes for revision", AssistantEmotion.SERIOUSNESS)
        }

        var createdCards = 0
        val sb = StringBuilder()
        sb.append(if (isHi) "Aapke notes se Revision Highlights:\n" else "Revision Notes Generated:\n")
        for (note in studyNotes.take(4)) {
            val cleanBody = note.content.replace("\n", " ").trim()
            sb.append("• ${note.title}: $cleanBody\n")
            if (onAddFlashcard != null && cleanBody.length >= 12) {
                onAddFlashcard("Key takeaway from '${note.title}'?", cleanBody, note.category.ifBlank { "Study" })
                createdCards++
            }
        }
        if (createdCards > 0) {
            sb.append("\nSaved $createdCards new flashcard(s) in your Planner Study tab.")
        }

        return OfficeAssistantResponse(
            intentName = "OFFICE_REVISION_NOTES",
            text = sb.toString().trim(),
            summary = "Generated revision notes ($createdCards flashcards)",
            emotion = AssistantEmotion.HAPPINESS,
            isNoteSaveable = true,
            suggestedNoteTitle = "Revision Summary"
        )
    }

    private fun draftProfessionalEmailOffline(rawCommand: String, userName: String, isHi: Boolean): OfficeAssistantResponse {
        val lower = rawCommand.lowercase()
        val topic = rawCommand
            .replace(Regex("""^(?:ek\s+)?(?:please\s+)?(?:draft|write|compose)\s+(?:a\s+|an\s+)?(?:professional\s+)?email\s*(?:about|for|regarding|on|to)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:ek\s+)?professional\s+email\s+draft\s+karo\s*(?:for|about|on)?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "Project Update & Follow-up" }

        val draft = when {
            lower.contains("leave") || lower.contains("sick") || lower.contains("chutti") -> """
                Subject: Leave Request – $userName

                Dear Manager,

                I am writing to request leave for today/the upcoming date due to personal/health reasons. I have ensured that urgent deliverables are up to date and will remain reachable for critical matters.

                Thank you for your understanding.

                Best regards,
                $userName
            """.trimIndent()

            lower.contains("meeting") || lower.contains("schedule") -> """
                Subject: Meeting Request – ${topic.replaceFirstChar { it.uppercase() }}

                Hi Team,

                I would like to schedule a brief meeting to align on ${topic}. Please share your available time slots so we can finalize the calendar invite.

                Agenda:
                1. Current status review
                2. Key blockers & next steps
                3. Action owners & timelines

                Best regards,
                $userName
            """.trimIndent()

            else -> """
                Subject: Regarding ${topic.replaceFirstChar { it.uppercase() }}

                Dear Recipient,

                I hope you are doing well. I am writing to follow up regarding ${topic} and share the latest status from my end. Please let me know if you need any additional details or would like to discuss next steps.

                Warm regards,
                $userName
            """.trimIndent()
        }

        val prefix = if (isHi) "Aapke liye professional email draft taiyar hai (aap ise Notes mein save ya copy kar sakte hain):\n\n" else ""
        return OfficeAssistantResponse(
            intentName = "OFFICE_EMAIL_DRAFT",
            text = prefix + draft,
            summary = "Drafted professional email",
            emotion = AssistantEmotion.HAPPINESS,
            isNoteSaveable = true,
            suggestedNoteTitle = "Email Draft: ${topic.take(28)}"
        )
    }

    private fun draftApplicationOrLetterOffline(rawCommand: String, userName: String, isHi: Boolean): OfficeAssistantResponse {
        val lower = rawCommand.lowercase()
        val isLeave = lower.contains("leave") || lower.contains("chutti")
        val isJob = lower.contains("job") || lower.contains("cover") || lower.contains("position")

        val draft = when {
            isLeave -> """
                To,
                The Manager / Principal,

                Subject: Application for Leave

                Respected Sir/Madam,

                I kindly request leave from work/classes for [Date(s)] due to urgent personal/medical reasons. I will complete all pending assignments promptly upon my return.

                Thanking you.

                Yours sincerely,
                $userName
            """.trimIndent()

            isJob -> """
                Subject: Application for [Role Title] – $userName

                Dear Hiring Manager,

                I am excited to submit my application for the [Role Title] position. With a strong track record in problem-solving, teamwork, and timely delivery, I am confident I can add immediate value to your team.

                I welcome the opportunity to discuss my qualifications further.

                Sincerely,
                $userName
            """.trimIndent()

            else -> """
                To,
                [Recipient Name / Authority],

                Subject: Formal Request / Application

                Respected Sir/Madam,

                I am writing to formally bring [topic/request] to your kind attention. Please consider this request and let me know the required next steps or documentation.

                Thank you for your time and support.

                Sincerely,
                $userName
            """.trimIndent()
        }

        val note = if (isHi) "Formal application template taiyar hai:\n\n" else ""
        return OfficeAssistantResponse(
            intentName = "OFFICE_LETTER_DRAFT",
            text = note + draft,
            summary = "Prepared formal letter template",
            emotion = AssistantEmotion.HAPPINESS,
            isNoteSaveable = true,
            suggestedNoteTitle = if (isLeave) "Leave Application" else "Formal Letter"
        )
    }

    private fun buildReportOutlineOffline(rawCommand: String, isHi: Boolean): OfficeAssistantResponse {
        val topic = rawCommand
            .replace(Regex("""^(?:prepare|create|write|make)\s+(?:a\s+)?report\s*(?:outline|structure)?\s*(?:on|for|about)?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "Work Progress" }
            .replaceFirstChar { it.uppercase() }

        val outline = """
            Report Outline: $topic
            1. Executive Summary — Goal, key outcomes, and bottom-line highlights.
            2. Background & Objectives — Scope and problem statement.
            3. Key Findings / Progress — Measurable metrics, milestones achieved, and data points.
            4. Challenges & Risks — Blockers encountered and mitigation plan.
            5. Recommendations & Next Steps — Action owners and target dates.
        """.trimIndent()

        return OfficeAssistantResponse(
            intentName = "OFFICE_REPORT_OUTLINE",
            text = if (isHi) "Report outline taiyar hai:\n$outline" else outline,
            summary = "Created report outline for $topic",
            emotion = AssistantEmotion.HAPPINESS,
            isNoteSaveable = true,
            suggestedNoteTitle = "Report Outline: ${topic.take(24)}"
        )
    }

    private fun summarizePastedText(body: String, isHi: Boolean): OfficeAssistantResponse {
        val sentences = body.split(Regex("""(?<=[.!?।])\s+""")).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.isEmpty()) {
            return OfficeAssistantResponse("OFFICE_TEXT_SUMMARY", "Please provide text after 'summarize:' to summarize.", "Empty text", AssistantEmotion.SERIOUSNESS)
        }
        val keyPoints = if (sentences.size <= 2) {
            sentences
        } else {
            listOf(sentences.first(), sentences[sentences.size / 2], sentences.last()).distinct()
        }
        val wordCount = body.split(Regex("""\s+""")).count { it.isNotBlank() }
        val summary = buildString {
            append(if (isHi) "Offline Summary ($wordCount shabd):\n" else "Key Takeaways ($wordCount words):\n")
            for (pt in keyPoints) {
                append("• ").append(pt).append("\n")
            }
        }.trim()
        return OfficeAssistantResponse("OFFICE_TEXT_SUMMARY", summary, "Summarized $wordCount words", AssistantEmotion.HAPPINESS)
    }

    private fun tryTranslateOffline(rawCommand: String, isOnline: Boolean): OfficeAssistantResponse? {
        val lower = rawCommand.lowercase()
        val match = bilingualPhrasebook.firstOrNull { (key, _, _) -> lower.contains(key) }
        if (match != null) {
            val wantsEnglish = lower.contains("to english") || lower.contains("english mein")
            val translated = if (wantsEnglish) match.second else match.third
            return OfficeAssistantResponse(
                intentName = "OFFICE_TRANSLATE_OFFLINE",
                text = "Translation: $translated",
                summary = "Translated phrase offline",
                emotion = AssistantEmotion.CURIOSITY
            )
        }
        if (!isOnline) {
            return OfficeAssistantResponse(
                intentName = "OFFICE_TRANSLATE_OFFLINE",
                text = "Basic common phrases can be translated offline, but full sentence translation requires an internet connection and Online or Office Mode.",
                summary = "Translation requires internet",
                emotion = AssistantEmotion.SERIOUSNESS
            )
        }
        return null
    }

    private fun findKnowledgeMatch(lower: String): QaEntry? {
        return offlineKnowledgeBase.firstOrNull { entry ->
            entry.keywords.any { kw -> lower.contains(kw) }
        }
    }

    private fun isHindiLike(lower: String): Boolean {
        return lower.any { it in '\u0900'..'\u097F' } ||
            listOf("karo", "banao", "batao", "samjhao", "likho", "kya", "mein", "chhutti", "chutti", "aaj").any {
                lower.contains(it)
            }
    }
}
