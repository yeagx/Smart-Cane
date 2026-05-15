package com.example.smart_cane

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smart_cane.databinding.ActivitySettingsBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var statusFadeRunnable: Runnable? = null

    companion object {
        private const val TELEGRAM_API = "https://api.telegram.org/bot"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(TelegramPrefs.PREFS_NAME, MODE_PRIVATE)

        binding.etBotToken.setText(prefs.getString(TelegramPrefs.KEY_BOT_TOKEN, "") ?: "")
        binding.etChatIds.setText(
            TelegramPrefs.formatChatIdsForEdit(TelegramPrefs.readChatIds(prefs))
        )

        if (TelegramPrefs.readChatIds(prefs).isNotEmpty()) {
            showStatus(getString(R.string.telegram_status_saved), R.color.status_clear)
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLinkCaretaker.setOnClickListener { fetchChatId() }
        binding.btnTestAlert.setOnClickListener { sendTestAlert() }
        binding.btnSave.setOnClickListener { saveSettings() }
    }

    private fun saveSettings() {
        val token = binding.etBotToken.text?.toString()?.trim() ?: ""
        val ids = TelegramPrefs.parseChatIdsFromText(binding.etChatIds.text?.toString() ?: "")

        prefs.edit().putString(TelegramPrefs.KEY_BOT_TOKEN, token).apply()
        TelegramPrefs.saveChatIds(prefs, ids)

        showStatus(getString(R.string.telegram_status_saved), R.color.status_clear)
        Toast.makeText(this, getString(R.string.settings_saved_toast), Toast.LENGTH_SHORT).show()
    }

    private fun showStatus(message: String, colorRes: Int) {
        binding.tvTelegramStatus.text = message
        binding.tvTelegramStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        statusFadeRunnable?.let { handler.removeCallbacks(it) }
        val fade = Runnable {
            if (TelegramPrefs.readChatIds(prefs).isEmpty()) {
                binding.tvTelegramStatus.text = getString(R.string.telegram_status_not_configured)
                binding.tvTelegramStatus.setTextColor(ContextCompat.getColor(this, R.color.card_stroke))
            }
        }
        statusFadeRunnable = fade
        handler.postDelayed(fade, 3000)
    }

    private fun fetchChatId() {
        val token = binding.etBotToken.text?.toString()?.trim() ?: ""
        if (token.isEmpty()) {
            showStatus(getString(R.string.telegram_status_no_token), R.color.status_warning)
            return
        }

        binding.btnLinkCaretaker.isEnabled = false
        showStatus("Fetching\u2026", R.color.card_stroke)

        Thread {
            try {
                val url = URL("$TELEGRAM_API$token/getUpdates")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(response)
                if (!json.getBoolean("ok")) {
                    handler.post {
                        binding.btnLinkCaretaker.isEnabled = true
                        showStatus(getString(R.string.telegram_status_failed, "Invalid token"), R.color.status_danger)
                    }
                    return@Thread
                }

                val results = json.getJSONArray("result")
                if (results.length() == 0) {
                    handler.post {
                        binding.btnLinkCaretaker.isEnabled = true
                        showStatus(getString(R.string.telegram_status_no_updates), R.color.status_warning)
                    }
                    return@Thread
                }

                val lastMsg = results.getJSONObject(results.length() - 1)
                val message = lastMsg.optJSONObject("message") ?: lastMsg.optJSONObject("my_chat_member")
                val chat = message?.optJSONObject("chat")
                val chatId = chat?.optLong("id")?.toString() ?: ""
                val firstName = chat?.optString("first_name", "") ?: ""

                if (chatId.isNotEmpty()) {
                    handler.post {
                        binding.btnLinkCaretaker.isEnabled = true
                        prefs.edit().putString(TelegramPrefs.KEY_BOT_TOKEN, token).apply()

                        val current =
                            TelegramPrefs.parseChatIdsFromText(binding.etChatIds.text?.toString() ?: "")
                        if (current.contains(chatId)) {
                            showStatus(getString(R.string.telegram_status_already_linked), R.color.status_warning)
                            return@post
                        }
                        current.add(chatId)
                        TelegramPrefs.saveChatIds(prefs, current)
                        binding.etChatIds.setText(TelegramPrefs.formatChatIdsForEdit(current))

                        val label = if (firstName.isNotEmpty()) firstName else chatId
                        showStatus(getString(R.string.telegram_status_linked, label), R.color.status_clear)
                    }
                } else {
                    handler.post {
                        binding.btnLinkCaretaker.isEnabled = true
                        showStatus(getString(R.string.telegram_status_no_updates), R.color.status_warning)
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    binding.btnLinkCaretaker.isEnabled = true
                    showStatus(getString(R.string.telegram_status_failed, e.localizedMessage ?: "Network error"), R.color.status_danger)
                }
            }
        }.start()
    }

    private fun sendTestAlert() {
        val token = binding.etBotToken.text?.toString()?.trim() ?: ""

        val parsed = TelegramPrefs.parseChatIdsFromText(binding.etChatIds.text?.toString() ?: "")
        val chatIds = if (parsed.isNotEmpty()) {
            parsed
        } else {
            LinkedHashSet(TelegramPrefs.readChatIds(prefs))
        }

        if (token.isEmpty()) {
            showStatus(getString(R.string.telegram_status_no_token), R.color.status_warning)
            return
        }
        if (chatIds.isEmpty()) {
            showStatus(getString(R.string.telegram_status_no_chat), R.color.status_warning)
            return
        }

        TelegramPrefs.saveChatIds(prefs, chatIds)
        prefs.edit().putString(TelegramPrefs.KEY_BOT_TOKEN, token).apply()

        Thread {
            try {
                val encodedMsg = URLEncoder.encode(getString(R.string.telegram_msg_test), "UTF-8")
                var failures = 0
                var attempts = 0
                for (chatId in chatIds) {
                    attempts++
                    try {
                        val url = URL("$TELEGRAM_API$token/sendMessage?chat_id=$chatId&text=$encodedMsg")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        val code = conn.responseCode
                        conn.disconnect()
                        if (code != 200) failures++
                    } catch (_: Exception) {
                        failures++
                    }
                }
                handler.post {
                    when {
                        failures == 0 ->
                            showStatus(getString(R.string.telegram_status_test_sent), R.color.status_clear)
                        failures == attempts ->
                            showStatus(getString(R.string.telegram_status_failed, "All sends failed"), R.color.status_danger)
                        else ->
                            showStatus(
                                getString(R.string.telegram_status_failed, "$failures / $attempts failed"),
                                R.color.status_warning
                            )
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    showStatus(getString(R.string.telegram_status_failed, e.localizedMessage ?: "Network error"), R.color.status_danger)
                }
            }
        }.start()
    }
}
