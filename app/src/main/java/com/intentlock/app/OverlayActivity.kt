package com.intentlock.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.intentlock.app.data.AppDatabase
import com.intentlock.app.data.PredictionResult
import com.intentlock.app.data.UnlockEvent
import com.intentlock.app.prediction.ContextEngine
import com.intentlock.app.prediction.FallbackPredictor
import com.intentlock.app.prediction.GeminiPredictor
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class OverlayActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var layoutPrediction: View
    private lateinit var layoutMainSelection: View
    private lateinit var layoutAllApps: View
    private lateinit var containerPredictions: LinearLayout
    private lateinit var gridAllApps: GridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.overlay_intentlock)

        layoutPrediction = findViewById(R.id.layoutPrediction)
        layoutMainSelection = findViewById(R.id.layoutMainSelection)
        layoutAllApps = findViewById(R.id.layoutAllApps)
        containerPredictions = findViewById(R.id.containerPredictions)
        gridAllApps = findViewById(R.id.gridAllApps)

        // Ensure we show the activity's main UI, not the service's overlay UI
        layoutPrediction.visibility = View.GONE
        layoutMainSelection.visibility = View.VISIBLE

        findViewById<Button>(R.id.btnBrowseAll).setOnClickListener {
            showAllApps()
        }

        findViewById<ImageButton>(R.id.btnBackFromAll).setOnClickListener {
            showPredictions()
        }

        findViewById<ImageButton>(R.id.btnDismiss).setOnClickListener {
            finish()
        }

        loadPredictions()
    }

    private fun loadPredictions() {
        scope.launch {
            val db = AppDatabase.getInstance(this@OverlayActivity)
            val contextEngine = ContextEngine(this@OverlayActivity)
            val ctx = contextEngine.collect()
            val installedApps = getInstalledPackages()
            
            var predictions: List<PredictionResult> = GeminiPredictor(this@OverlayActivity).predictMultiple(ctx, installedApps)
            
            if (predictions.isEmpty()) {
                val fallback = FallbackPredictor(db.unlockDao(), this@OverlayActivity).predict(ctx)
                if (fallback != null) {
                    predictions = listOf(fallback)
                }
            }

            containerPredictions.removeAllViews()
            if (predictions.isEmpty()) {
                // If still empty, just show all apps or a prominent button
                val tv = TextView(this@OverlayActivity).apply {
                    text = "No predictions. Select an app below."
                    setTextColor(android.graphics.Color.WHITE)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 48, 0, 48)
                }
                containerPredictions.addView(tv)
                showAllApps() // Automatically show all apps if no predictions
            } else {
                predictions.forEach { prediction ->
                    val view = LayoutInflater.from(this@OverlayActivity).inflate(R.layout.item_prediction, containerPredictions, false)
                    view.findViewById<TextView>(R.id.tvAppName).text = prediction.appName
                    view.findViewById<TextView>(R.id.tvConfidence).text = "${prediction.confidence}%"
                    view.findViewById<TextView>(R.id.tvReason).text = prediction.reason
                    try {
                        val icon = packageManager.getApplicationIcon(prediction.packageName)
                        view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(icon)
                    } catch (_: Exception) {}
                    
                    view.setOnClickListener {
                        selectApp(prediction.packageName)
                    }
                    containerPredictions.addView(view)
                }
            }
        }
    }

    private fun showAllApps() {
        layoutMainSelection.visibility = View.GONE
        layoutAllApps.visibility = View.VISIBLE
        
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { packageManager.getApplicationLabel(it).toString() }
            }
            
            gridAllApps.adapter = object : BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(position: Int) = apps[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val view = convertView ?: LayoutInflater.from(this@OverlayActivity).inflate(R.layout.item_app_grid, parent, false)
                    val app = apps[position]
                    view.findViewById<TextView>(R.id.tvAppName).text = packageManager.getApplicationLabel(app)
                    view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(packageManager.getApplicationIcon(app))
                    view.setOnClickListener {
                        selectApp(app.packageName)
                    }
                    return view
                }
            }
        }
    }

    private fun showPredictions() {
        layoutAllApps.visibility = View.GONE
        layoutMainSelection.visibility = View.VISIBLE
    }

    private fun selectApp(packageName: String) {
        logUnlock(packageName, true)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        finish()
    }

    private fun getInstalledPackages(): List<String> {
        return packageManager.getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName }
    }

    private fun logUnlock(pkg: String, wasCorrect: Boolean) {
        scope.launch(Dispatchers.IO) {
            val cal = Calendar.getInstance()
            val event = UnlockEvent(
                timestamp = cal.timeInMillis,
                hour = cal.get(Calendar.HOUR_OF_DAY),
                dayOfWeek = SimpleDateFormat("EEEE", Locale.ENGLISH).format(cal.time),
                packageName = pkg,
                wasCorrect = wasCorrect
            )
            AppDatabase.getInstance(this@OverlayActivity).unlockDao().insert(event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
