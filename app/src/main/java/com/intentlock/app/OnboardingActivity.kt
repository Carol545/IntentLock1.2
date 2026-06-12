package com.intentlock.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.intentlock.app.data.AppDatabase
import com.intentlock.app.data.AppSchedule
import com.intentlock.app.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    private var step = 0
    private lateinit var installedApps: List<ApplicationInfo>

    private data class Step(
        val title: String,
        val desc: String,
        val grantLabel: String,
        val isGranted: () -> Boolean,
        val onGrant: () -> Unit
    )

    private val steps by lazy {
        listOf(
            Step(
                title = getString(R.string.onboarding_title_overlay),
                desc  = getString(R.string.onboarding_desc_overlay),
                grantLabel = getString(R.string.btn_grant),
                isGranted  = { PermissionHelper.hasOverlayPermission(this) },
                onGrant    = { PermissionHelper.openOverlaySettings(this) }
            ),
            Step(
                title = getString(R.string.onboarding_title_accessibility),
                desc  = getString(R.string.onboarding_desc_accessibility),
                grantLabel = getString(R.string.btn_grant),
                isGranted  = { PermissionHelper.isAccessibilityServiceEnabled(this) },
                onGrant    = { PermissionHelper.openAccessibilitySettings(this) }
            ),
            Step(
                title = getString(R.string.onboarding_title_usage),
                desc  = getString(R.string.onboarding_desc_usage),
                grantLabel = getString(R.string.btn_grant),
                isGranted  = { PermissionHelper.hasUsageStatsPermission(this) },
                onGrant    = { PermissionHelper.openUsageAccessSettings(this) }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        
        installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { packageManager.getApplicationLabel(it).toString() }

        renderStep()

        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            steps[step].onGrant()
        }

        findViewById<Button>(R.id.btnNext).setOnClickListener {
            if (step < steps.size - 1) {
                step++
                renderStep()
            } else {
                showScheduleStep()
            }
        }

        findViewById<Button>(R.id.btnAddSchedule).setOnClickListener {
            addScheduleRow()
        }

        findViewById<Button>(R.id.btnFinishOnboarding).setOnClickListener {
            saveAndFinish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (step < steps.size) renderStep()
    }

    private fun renderStep() {
        findViewById<View>(R.id.layoutStandardSteps).visibility = View.VISIBLE
        findViewById<View>(R.id.layoutScheduleStep).visibility = View.GONE

        val s = steps[step]
        val granted = s.isGranted()

        findViewById<TextView>(R.id.tvStepIndicator).text = "Step ${step + 1} of 4"
        findViewById<TextView>(R.id.tvStepTitle).text = s.title
        findViewById<TextView>(R.id.tvStepDesc).text = s.desc

        val btnGrant = findViewById<Button>(R.id.btnGrant)
        val btnNext  = findViewById<Button>(R.id.btnNext)

        if (granted) {
            btnGrant.text = "✓ Granted"
            btnGrant.isEnabled = false
        } else {
            btnGrant.text = s.grantLabel
            btnGrant.isEnabled = true
        }
        btnNext.text = "Next"
    }

    private fun showScheduleStep() {
        step = steps.size
        findViewById<View>(R.id.layoutStandardSteps).visibility = View.GONE
        findViewById<View>(R.id.layoutScheduleStep).visibility = View.VISIBLE
        addScheduleRow() // Initial row
    }

    private fun addScheduleRow() {
        val container = findViewById<LinearLayout>(R.id.containerSchedules)
        val row = LayoutInflater.from(this).inflate(R.layout.item_schedule, container, false)
        
        val spinnerApps = row.findViewById<Spinner>(R.id.spinnerApps)
        val appNames = installedApps.map { packageManager.getApplicationLabel(it).toString() }
        spinnerApps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appNames)

        val hours = (0..23).map { String.format("%02d:00", it) }
        val startSpinner = row.findViewById<Spinner>(R.id.spinnerStartHour)
        val endSpinner = row.findViewById<Spinner>(R.id.spinnerEndHour)
        
        startSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, hours)
        endSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, hours)
        
        // Default to a 9-17 schedule for first row
        startSpinner.setSelection(9)
        endSpinner.setSelection(17)

        row.findViewById<ImageButton>(R.id.btnRemove).setOnClickListener {
            container.removeView(row)
        }

        container.addView(row)
    }

    private fun saveAndFinish() {
        val container = findViewById<LinearLayout>(R.id.containerSchedules)
        val schedules = mutableListOf<AppSchedule>()

        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            val appIndex = row.findViewById<Spinner>(R.id.spinnerApps).selectedItemPosition
            val startHour = row.findViewById<Spinner>(R.id.spinnerStartHour).selectedItemPosition
            val endHour = row.findViewById<Spinner>(R.id.spinnerEndHour).selectedItemPosition
            
            val app = installedApps[appIndex]
            schedules.add(AppSchedule(
                packageName = app.packageName,
                appName = packageManager.getApplicationLabel(app).toString(),
                startHour = startHour,
                endHour = endHour
            ))
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(this@OnboardingActivity).unlockDao()
                dao.clearSchedules()
                schedules.forEach { dao.insertSchedule(it) }
            }
            finish()
        }
    }
}
