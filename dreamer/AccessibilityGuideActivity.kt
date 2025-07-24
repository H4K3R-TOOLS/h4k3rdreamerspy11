package com.h4k3r.dreamer

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*

class AccessibilityGuideActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var skipButton: Button
    private lateinit var openSettingsButton: Button
    private lateinit var statusLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var indicators: List<View>

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var checkingStatus = false

    // Guide steps data
    private val guideSteps = listOf(
        GuideStep(
            imageRes = R.drawable.accessibility_guide_step1,
            title = "Step 1: Open Settings",
            description = "Tap the 'Open Settings' button below to navigate to Accessibility settings"
        ),
        GuideStep(
            imageRes = R.drawable.accessibility_guide_step2,
            title = "Step 2: Find Dreamer",
            description = "Scroll down and look for 'Dreamer' in the Downloaded Services section"
        ),
        GuideStep(
            imageRes = R.drawable.accessibility_guide_step3,
            title = "Step 3: Enable Service",
            description = "Tap on 'Dreamer' and toggle the switch to ON position"
        ),
        GuideStep(
            imageRes = R.drawable.accessibility_guide_step4,
            title = "Step 4: Confirm",
            description = "Tap 'Allow' on the confirmation dialog to grant accessibility permission"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accessibility_guide)

        initViews()
        setupViewPager()
        setupButtons()

        // Start checking accessibility status
        startAccessibilityCheck()
    }

    private fun initViews() {
        viewPager = findViewById(R.id.guideViewPager)
        skipButton = findViewById(R.id.skipButton)
        openSettingsButton = findViewById(R.id.openSettingsButton)
        statusLayout = findViewById(R.id.statusLayout)
        statusText = findViewById(R.id.statusText)

        indicators = listOf(
            findViewById(R.id.indicator1),
            findViewById(R.id.indicator2),
            findViewById(R.id.indicator3),
            findViewById(R.id.indicator4)
        )
    }

    private fun setupViewPager() {
        val adapter = GuideStepAdapter(guideSteps)
        viewPager.adapter = adapter

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicators(position)
                updateButtonText(position)
            }
        })
    }

    private fun updateIndicators(position: Int) {
        indicators.forEachIndexed { index, view ->
            view.setBackgroundResource(
                if (index == position) R.drawable.indicator_active
                else R.drawable.indicator_inactive
            )
        }
    }

    private fun updateButtonText(position: Int) {
        openSettingsButton.text = when (position) {
            0 -> "Open Settings"
            guideSteps.size - 1 -> "Check Status"
            else -> "Next"
        }
    }

    private fun setupButtons() {
        skipButton.setOnClickListener {
            // Skip to file-based strategy
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("force_strategy", 2)
            }
            startActivity(intent)
            finish()
        }

        openSettingsButton.setOnClickListener {
            val currentPage = viewPager.currentItem

            when {
                currentPage == 0 -> {
                    // Open accessibility settings
                    openAccessibilitySettings()
                    // Move to next page
                    viewPager.currentItem = 1
                }
                currentPage == guideSteps.size - 1 -> {
                    // Check status
                    checkAccessibilityStatus()
                }
                else -> {
                    // Next page
                    viewPager.currentItem = currentPage + 1
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)

        Toast.makeText(
            this,
            "Find 'Dreamer' and enable it",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun startAccessibilityCheck() {
        scope.launch {
            while (!checkingStatus) {
                if (isAccessibilityServiceEnabled()) {
                    onAccessibilityEnabled()
                    break
                }
                delay(1000)
            }
        }
    }

    private fun checkAccessibilityStatus() {
        checkingStatus = true
        statusLayout.visibility = View.VISIBLE
        statusText.text = "Checking accessibility status..."

        scope.launch {
            delay(1000) // Simulate checking

            if (isAccessibilityServiceEnabled()) {
                onAccessibilityEnabled()
            } else {
                statusText.text = "Accessibility not enabled. Please try again."
                delay(2000)
                statusLayout.visibility = View.GONE
                checkingStatus = false
            }
        }
    }

    private fun onAccessibilityEnabled() {
        statusLayout.visibility = View.VISIBLE
        statusText.text = "✓ Accessibility enabled! Setting up..."

        // Notify main activity
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("accessibility_enabled", true)
        }
        startActivity(intent)

        scope.launch {
            delay(2000)
            finish()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)

            if (enabledService != null && enabledService.packageName == packageName) {
                return true
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        // Check if accessibility was enabled while in settings
        if (isAccessibilityServiceEnabled()) {
            onAccessibilityEnabled()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // Data class for guide steps
    data class GuideStep(
        val imageRes: Int,
        val title: String,
        val description: String
    )

    // ViewPager adapter
    inner class GuideStepAdapter(
        private val steps: List<GuideStep>
    ) : RecyclerView.Adapter<GuideStepViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuideStepViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.guide_step_layout, parent, false)
            return GuideStepViewHolder(view)
        }

        override fun onBindViewHolder(holder: GuideStepViewHolder, position: Int) {
            holder.bind(steps[position])
        }

        override fun getItemCount() = steps.size
    }

    // ViewHolder
    inner class GuideStepViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.stepImage)
        private val titleText: TextView = itemView.findViewById(R.id.stepTitle)
        private val descriptionText: TextView = itemView.findViewById(R.id.stepDescription)

        fun bind(step: GuideStep) {
            // In real app, load actual images
            // imageView.setImageResource(step.imageRes)
            imageView.setBackgroundColor(0xFF444444.toInt()) // Placeholder

            titleText.text = step.title
            descriptionText.text = step.description
        }
    }
}