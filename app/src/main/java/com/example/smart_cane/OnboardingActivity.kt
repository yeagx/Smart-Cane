package com.example.smart_cane

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.smart_cane.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private data class Page(val iconRes: Int, val titleRes: Int, val descRes: Int)

    private val pages = listOf(
        Page(R.string.onboarding_welcome_icon, R.string.onboarding_welcome_title, R.string.onboarding_welcome_desc),
        Page(R.string.onboarding_blind_icon, R.string.onboarding_blind_title, R.string.onboarding_blind_desc),
        Page(R.string.onboarding_caretaker_icon, R.string.onboarding_caretaker_title, R.string.onboarding_caretaker_desc),
        Page(R.string.onboarding_setup_icon, R.string.onboarding_setup_title, R.string.onboarding_setup_desc),
        Page(R.string.onboarding_perms_icon, R.string.onboarding_perms_title, R.string.onboarding_perms_desc),
    )

    companion object {
        private const val PREFS_NAME = "smartcane_prefs"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isOnboardingDone()) {
            launchMain()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupDots()
        setupButtons()
    }

    private fun isOnboardingDone(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_DONE, false)

    private fun markOnboardingDone() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_DONE, true)
            .apply()
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ── ViewPager ────────────────────────────────────────────────────

    private fun setupViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = pages.size
            override fun createFragment(position: Int): Fragment {
                val page = pages[position]
                return OnboardingPageFragment.newInstance(
                    getString(page.iconRes),
                    getString(page.titleRes),
                    getString(page.descRes)
                )
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                updateButtons(position)
            }
        })
    }

    // ── Dot Indicators ───────────────────────────────────────────────

    private fun setupDots() {
        val container = binding.dotsContainer
        for (i in pages.indices) {
            val dot = View(this).apply {
                val size = (10 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = (4 * resources.displayMetrics.density).toInt()
                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                }
                setBackgroundResource(R.drawable.dot_inactive)
            }
            container.addView(dot)
        }
        updateDots(0)
    }

    private fun updateDots(activePosition: Int) {
        val container = binding.dotsContainer
        for (i in 0 until container.childCount) {
            val dot = container.getChildAt(i)
            dot.setBackgroundResource(
                if (i == activePosition) R.drawable.dot_active else R.drawable.dot_inactive
            )
        }
    }

    // ── Buttons ──────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < pages.size - 1) {
                binding.viewPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }

        binding.btnSkip.setOnClickListener {
            finishOnboarding()
        }

        updateButtons(0)
    }

    private fun updateButtons(position: Int) {
        val isLast = position == pages.size - 1
        binding.btnNext.text = if (isLast) {
            getString(R.string.onboarding_get_started)
        } else {
            getString(R.string.onboarding_next)
        }
        binding.btnSkip.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
    }

    private fun finishOnboarding() {
        markOnboardingDone()
        launchMain()
    }
}
