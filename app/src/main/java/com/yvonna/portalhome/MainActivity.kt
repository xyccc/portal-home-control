package com.yvonna.portalhome

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvonna.portalhome.databinding.ActivityMainBinding
import com.yvonna.portalhome.databinding.ItemActionBinding
import com.yvonna.portalhome.databinding.ItemDeviceBinding
import com.yvonna.portalhome.databinding.ItemSectionBinding
import com.yvonna.portalhome.model.LightDevice
import com.yvonna.portalhome.model.Source
import com.yvonna.portalhome.net.HueClient
import com.yvonna.portalhome.net.NestSdmClient
import com.yvonna.portalhome.net.TuyaClient
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: Config
    private lateinit var hue: HueClient
    private lateinit var tuya: TuyaClient
    private lateinit var nest: NestSdmClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = Config(this)
        hue = HueClient(config)
        tuya = TuyaClient(config)
        nest = NestSdmClient(config)

        binding.btnRefresh.setOnClickListener { load() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        binding.container.removeAllViews()
        if (!config.hueConfigured && !config.tuyaConfigured && !config.nestConfigured) {
            binding.txtStatus.text = "Nothing configured yet — tap Settings."
            return
        }
        binding.txtStatus.text = "Loading…"
        lifecycleScope.launch {
            if (config.hueConfigured) loadLights("Philips Hue") { hue.listLights() }
            if (config.tuyaConfigured) loadLights("Feit (Tuya)") { tuya.listLights() }
            if (config.nestConfigured) loadDoorbells()
            binding.txtStatus.text = "Updated."
        }
    }

    private suspend fun loadLights(section: String, fetch: suspend () -> List<LightDevice>) {
        val grid = addSection(section)
        val result = runCatching { withContext(Dispatchers.IO) { fetch() } }
        result.onSuccess { lights ->
            if (lights.isEmpty()) addInfo("No lights found.", grid)
            else lights.forEach { addLight(it, grid) }
        }.onFailure { addInfo("Error: ${it.message}", grid) }
    }

    private suspend fun loadDoorbells() {
        val grid = addSection("Nest Doorbell")
        val result = runCatching { withContext(Dispatchers.IO) { nest.listDoorbells() } }
        result.onSuccess { doorbells ->
            if (doorbells.isEmpty()) addInfo("No cameras/doorbells found.", grid)
            doorbells.forEach { d ->
                val row = ItemActionBinding.inflate(layoutInflater, grid, false)
                row.txtName.text = "${d.displayName} (${if (d.online) "online" else "offline"})"
                row.btnAction.text = "Live"
                row.btnAction.setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, WebRtcDoorbellActivity::class.java)
                            .putExtra(WebRtcDoorbellActivity.EXTRA_RESOURCE, d.resourceName)
                    )
                }
                addCardToGrid(grid, row.root)
            }
        }.onFailure { addInfo("Error: ${it.message}", grid) }
    }

    private fun addLight(light: LightDevice, grid: GridLayout) {
        val row = ItemDeviceBinding.inflate(layoutInflater, grid, false)
        row.txtName.text = light.name

        // Nullable var so the lambda can re-attach itself by reference after a revert.
        var toggleListener: android.widget.CompoundButton.OnCheckedChangeListener? = null
        toggleListener = android.widget.CompoundButton.OnCheckedChangeListener { view, isChecked ->
            view.isEnabled = false
            lifecycleScope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        when (light.source) {
                            Source.HUE -> hue.setOn(light.id, isChecked)
                            Source.TUYA -> tuya.setOn(light.id, isChecked)
                        }
                    }
                }
                if (ok.isFailure) {
                    toast("Toggle failed: ${ok.exceptionOrNull()?.message}")
                    row.swToggle.setOnCheckedChangeListener(null)
                    row.swToggle.isChecked = !isChecked
                    row.swToggle.setOnCheckedChangeListener(toggleListener)
                }
                view.isEnabled = true
            }
        }
        row.swToggle.setOnCheckedChangeListener(null)
        row.swToggle.isChecked = light.on
        row.swToggle.setOnCheckedChangeListener(toggleListener)

        // Brightness slider — only for dimmable lights.
        if (light.supportsBrightness) {
            row.sliderBrightness.visibility = View.VISIBLE
            row.sliderBrightness.value = (light.brightness ?: 1).coerceIn(1, 100).toFloat()
            // Commit on release rather than on every step, to avoid flooding the bridge.
            row.sliderBrightness.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    val level = slider.value.toInt()
                    slider.isEnabled = false
                    lifecycleScope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                when (light.source) {
                                    Source.HUE -> hue.setBrightness(light.id, level)
                                    Source.TUYA -> tuya.setBrightness(light.id, level)
                                }
                            }
                        }
                        if (ok.isFailure) {
                            toast("Brightness failed: ${ok.exceptionOrNull()?.message}")
                        } else {
                            // Setting brightness also turns the light on; keep the toggle in sync.
                            row.swToggle.setOnCheckedChangeListener(null)
                            row.swToggle.isChecked = true
                            row.swToggle.setOnCheckedChangeListener(toggleListener)
                        }
                        slider.isEnabled = true
                    }
                }
            })
        } else {
            row.sliderBrightness.visibility = View.GONE
        }

        addCardToGrid(grid, row.root)
    }

    private fun addSection(title: String): GridLayout {
        val header = ItemSectionBinding.inflate(layoutInflater, binding.container, false)
        header.root.text = title
        binding.container.addView(header.root)

        return GridLayout(this).apply {
            columnCount = cardColumns()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            binding.container.addView(this)
        }
    }

    private fun addInfo(text: String, grid: GridLayout? = null) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.portal_ink_muted))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.VISIBLE
        }
        if (grid == null) {
            binding.container.addView(tv)
        } else {
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(0, grid.columnCount, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(8))
            }
            grid.addView(tv, lp)
        }
    }

    private fun addCardToGrid(grid: GridLayout, view: View) {
        val lp = GridLayout.LayoutParams().apply {
            width = 0
            height = dp(126)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(6), dp(6), dp(6), dp(6))
        }
        grid.addView(view, lp)
    }

    private fun cardColumns(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return when {
            widthDp >= 1280 -> 5
            widthDp >= 900 -> 4
            widthDp >= 640 -> 3
            widthDp >= 560 -> 2
            else -> 1
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
