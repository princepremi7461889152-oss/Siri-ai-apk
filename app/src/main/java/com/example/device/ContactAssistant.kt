package com.example.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.ContactMatch
import kotlin.math.min

sealed class ContactSearchOutcome {
    data object PermissionMissing : ContactSearchOutcome()
    data class NotFound(val query: String) : ContactSearchOutcome()
    data class SingleMatch(val contact: ContactMatch) : ContactSearchOutcome()
    data class DuplicateMatches(val query: String, val matches: List<ContactMatch>) : ContactSearchOutcome()
}

class ContactAssistant(private val context: Context) {

    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun searchContacts(rawQuery: String): ContactSearchOutcome {
        if (!hasContactsPermission()) {
            return ContactSearchOutcome.PermissionMissing
        }

        val cleanedQuery = rawQuery
            .replace(Regex("""^(?:my\s+|mere\s+|meri\s+|to\s+)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:ko|ka|ki|ke|please|ko\s+call|ko\s+phone)$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanedQuery.isBlank()) {
            return ContactSearchOutcome.NotFound(rawQuery)
        }

        val allContacts = loadPhoneContacts()
        if (allContacts.isEmpty()) {
            return ContactSearchOutcome.NotFound(cleanedQuery)
        }

        val normQuery = normalizeForSearch(cleanedQuery)
        val exactMatches = allContacts.filter { normalizeForSearch(it.name) == normQuery }
        if (exactMatches.size == 1) {
            return ContactSearchOutcome.SingleMatch(exactMatches.first())
        }
        if (exactMatches.size > 1) {
            return ContactSearchOutcome.DuplicateMatches(cleanedQuery, exactMatches.take(5))
        }

        val queryTokens = normQuery.split(" ").filter { it.isNotBlank() }
        val scored = allContacts.mapNotNull { contact ->
            val normName = normalizeForSearch(contact.name)
            val score = scoreMatch(normQuery, queryTokens, normName)
            if (score > 0) contact to score else null
        }.sortedWith(compareByDescending<Pair<ContactMatch, Int>> { it.second }.thenBy { it.first.name })

        if (scored.isEmpty()) {
            return ContactSearchOutcome.NotFound(cleanedQuery)
        }

        val topScore = scored.first().second
        val topMatches = scored.filter { it.second >= (topScore - 15).coerceAtLeast(55) }
            .map { it.first }
            .distinctBy { "${it.name.lowercase()}|${normalizePhone(it.phoneNumber)}" }
            .take(5)

        return if (topMatches.size == 1) {
            ContactSearchOutcome.SingleMatch(topMatches.first())
        } else {
            ContactSearchOutcome.DuplicateMatches(cleanedQuery, topMatches)
        }
    }

    private fun loadPhoneContacts(): List<ContactMatch> {
        val results = mutableListOf<ContactMatch>()
        val seenKeys = mutableSetOf<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getString(idIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val number = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val typeInt = if (typeIdx >= 0) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.resources,
                        typeInt,
                        "Mobile"
                    ).toString()

                    if (name.isNotBlank() && number.isNotBlank()) {
                        val dedupKey = "${name.lowercase()}|${normalizePhone(number)}"
                        if (seenKeys.add(dedupKey)) {
                            results.add(
                                ContactMatch(
                                    id = id.ifBlank { "${name.hashCode()}_${results.size}" },
                                    name = name,
                                    phoneNumber = number,
                                    typeLabel = typeLabel
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w("ContactAssistant", "Contacts permission denied during query", e)
        } catch (e: Exception) {
            Log.e("ContactAssistant", "Failed to query contacts", e)
        }
        return results
    }

    private fun scoreMatch(normQuery: String, queryTokens: List<String>, normName: String): Int {
        if (normName.isBlank() || normQuery.isBlank()) return 0
        if (normName == normQuery) return 100
        if (normName.startsWith(normQuery)) return 92
        val nameTokens = normName.split(" ").filter { it.isNotBlank() }
        if (nameTokens.any { it == normQuery }) return 88
        if (nameTokens.any { it.startsWith(normQuery) } && normQuery.length >= 2) return 82
        if (normName.contains(normQuery) && normQuery.length >= 3) return 75
        if (queryTokens.isNotEmpty() && queryTokens.all { qt -> nameTokens.any { it.contains(qt) } }) return 72

        val dist = levenshtein(normQuery, normName)
        val maxLen = maxOf(normQuery.length, normName.length)
        if (maxLen >= 4 && dist <= 2) {
            return (70 - dist * 8).coerceAtLeast(50)
        }
        return 0
    }

    private fun normalizePhone(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }

    private fun normalizeForSearch(raw: String): String {
        val latin = if (raw.any { it in '\u0900'..'\u097F' }) {
            UniversalAppLauncher.devanagariToLatin(raw)
        } else {
            raw
        }
        return latin.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = i - 1
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) {
                    prev
                } else {
                    1 + min(prev, min(dp[j], dp[j - 1]))
                }
                prev = temp
            }
        }
        return dp[b.length]
    }
}
