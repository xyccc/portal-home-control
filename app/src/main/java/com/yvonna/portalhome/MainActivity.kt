package com.yvonna.portalhome

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        addSection(section)
        val result = runCatching { withContext(Dispatchers.IO) { fetch() } }
        result.onSuccess { lights ->
            if (lights.isEmpty()) addInfo("No lights found.")
            else lights.forEach { addLight(it) }
        }.onFailure { addInfo("Error: ${it.message}") }
    }

    private suspend fun loadDoorbells() {
        addSection("Nest Doorbell")
        val result = runCatching { withContext(Dispatchers.IO) { nest.listDoorbells() } }
        result.onSuccess { doorbells ->
            if (doorbells.isEmpty()) addInfo("No cameras/doorbells found.")
            doorbells.forEach { d ->
                val row = ItemActionBinding.inflate(layoutInflater, binding.container, false)
                row.txtName.text = "${d.displayName} (${if (d.online) "online" else "offline"})"
                row.btnAction.text = "Live"
                row.btnAction.setOnClickListener { openStream(d.resourceName) }
                binding.container.addView(row.root)
            }
        }.onFailure { addInfo("Error: ${it.message}") }
    }

    private fun openStream(resourceName: String) {
        binding.txtStatus.text = "Requesting stream…"
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { nest.generateRtspStream(resourceName) } }
                .onSuccess { url ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("RTSP stream")
                        .setMessage(url)
                        .setPositiveButton("OK", null)
                        .show()
                }
                .onFailure { toast(it.message ?: "Stream failed") }
            binding.txtStatus.text = "Updated."
        }
    }

    private fun addLight(light: LightDevice) {
        val row = ItemDeviceBinding.inflate(layoutInflater, binding.container, false)
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

        // Brightness slider — only for dimmable lights (currently Hue).
        if (light.supportsBrightness && light.source == Source.HUE) {
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
                            withContext(Dispatchers.IO) { hue.setBrightness(light.id, level) }
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

        binding.container.addView(row.root)
    }

    private fun addSection(title: String) {
        val header = ItemSectionBinding.inflate(layoutInflater, binding.container, false)
        header.root.text = title
        binding.container.addView(header.root)
    }

    private fun addInfo(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setPadding(0, 8, 0, 8)
            visibility = View.VISIBLE
        }
        binding.container.addView(tv)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
