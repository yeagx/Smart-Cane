package com.example.smart_cane

import android.content.SharedPreferences

/**
 * Persists multiple Telegram chat IDs and migrates legacy single [KEY_CHAT_ID_LEGACY].
 */
object TelegramPrefs {

    const val PREFS_NAME = "smartcane_prefs"
    const val KEY_BOT_TOKEN = "bot_token"
    private const val KEY_CHAT_ID_LEGACY = "chat_id"
    private const val KEY_CHAT_IDS = "chat_ids"

    fun readChatIds(prefs: SharedPreferences): List<String> {
        migrateLegacyIfNeeded(prefs)
        val stored = prefs.getStringSet(KEY_CHAT_IDS, null)
        if (stored != null) {
            return stored.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        }
        return emptyList()
    }

    /** Parse text field: one ID per line or comma-separated. Order preserved where possible. */
    fun parseChatIdsFromText(text: String): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        for (line in text.lines()) {
            for (part in line.split(',')) {
                val id = part.trim()
                if (id.isNotEmpty()) out.add(id)
            }
        }
        return out
    }

    fun formatChatIdsForEdit(ids: Collection<String>): String =
        ids.distinct().joinToString("\n")

    fun saveChatIds(prefs: SharedPreferences, ids: Collection<String>) {
        val unique = ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val editor = prefs.edit()
        if (unique.isEmpty()) {
            editor.remove(KEY_CHAT_IDS)
        } else {
            editor.putStringSet(KEY_CHAT_IDS, HashSet(unique))
        }
        editor.remove(KEY_CHAT_ID_LEGACY).apply()
    }

    private fun migrateLegacyIfNeeded(prefs: SharedPreferences) {
        if (prefs.contains(KEY_CHAT_IDS)) return
        val legacy = prefs.getString(KEY_CHAT_ID_LEGACY, "")?.trim().orEmpty()
        if (legacy.isEmpty()) return
        prefs.edit()
            .putStringSet(KEY_CHAT_IDS, HashSet(setOf(legacy)))
            .remove(KEY_CHAT_ID_LEGACY)
            .apply()
    }
}
