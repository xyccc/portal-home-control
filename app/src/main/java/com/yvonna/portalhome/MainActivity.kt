package com.yvonna.portalhome

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvonna.portalhome.databinding.ActivityMainBinding
import com.yvonna.portalhome.databinding.ItemActionBinding
import com.yvonna.portalhome.databinding.ItemDeviceBinding
import com.yvonna.portalhome.databinding.ItemSectionBinding
import com.yvonna.portalhome.databinding.ItemSceneBinding
import com.yvonna.portalhome.model.AppRoom
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

    /** Set to true when we need to re-fetch everything on resume (e.g. after Settings). */
    private var needsReload = true

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
            needsReload = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (needsReload) {
            needsReload = false
            load()
        }
    }

    private fun load() {
        binding.container.removeAllViews()
        if (!config.hueConfigured && !config.tuyaConfigured && !config.nestConfigured) {
            binding.txtStatus.text = "Nothing configured yet — tap Settings."
            return
        }
        binding.txtStatus.text = "Loading…"
        lifecycleScope.launch {
            val hueResult = if (config.hueConfigured) {
                runCatching { withContext(Dispatchers.IO) { hue.listLights() } }
            } else null
            val tuyaResult = if (config.tuyaConfigured) {
                runCatching { withContext(Dispatchers.IO) { tuya.listLights() } }
            } else null

            val hueLights = hueResult?.getOrNull().orEmpty()
            val tuyaLights = tuyaResult?.getOrNull().orEmpty()
            val allLights = hueLights + tuyaLights

            if (allLights.isNotEmpty()) {
                val assignments = assignLightsToRooms(allLights, config.appRooms)
                addControls(allLights)
                addRoomSections(assignments)
            }

            hueResult?.exceptionOrNull()?.let { addErrorSection("Philips Hue", it) }
            tuyaResult?.exceptionOrNull()?.let { addErrorSection("Feit (Tuya)", it) }
            if (config.nestConfigured) loadDoorbells()
            binding.txtStatus.text = "Updated."
        }
    }

    private fun addLightSection(section: String, result: Result<List<LightDevice>>) {
        val grid = addSection(section)
        result.onSuccess { lights ->
            if (lights.isEmpty()) addInfo("No lights found.", grid)
            else lights.forEach { addLight(it, grid) }
        }.onFailure { addInfo("Error: ${it.message}", grid) }
    }

    private fun addControls(allLights: List<LightDevice>) {
        val grid = addSection("Controls")
        addSceneCard(
            grid = grid,
            title = "Whole Home",
            primary = "On",
            secondary = "Off",
            onPrimary = { applyToLights(allLights, on = true) },
            onSecondary = { applyToLights(allLights, on = false) },
        )
        if (allLights.any { it.supportsBrightness }) {
            addSceneCard(
                grid = grid,
                title = "Whole Home Brightness",
                primary = "Bright",
                secondary = "Dim",
                onPrimary = { applyToLights(allLights, on = true, brightness = 100) },
                onSecondary = { applyToLights(allLights, on = true, brightness = 30) },
            )
        }
    }

    private fun addRoomSections(assignments: LinkedHashMap<String, List<LightDevice>>) {
        assignments.forEach { (room, roomLights) ->
            val grid = addSection(room)
            addRoomLightCards(grid, room, roomLights)
        }
    }

    private fun addRoomLightCards(grid: GridLayout, room: String, lights: List<LightDevice>) {
        val sourceGroups = lights.groupBy { it.source }
        if (room == UNASSIGNED_ROOM) {
            lights.forEach { addLight(it, grid) }
            return
        }

        sourceGroups.forEach { (source, sourceLights) ->
            if (sourceLights.size == 1) {
                addLight(sourceLights.first(), grid)
            } else {
                addLightGroup(room, source, sourceGroups.size, sourceLights, grid)
            }
        }
    }

    private fun assignLightsToRooms(
        lights: List<LightDevice>,
        rooms: List<AppRoom>,
    ): LinkedHashMap<String, List<LightDevice>> {
        if (rooms.isEmpty()) return linkedMapOf(UNASSIGNED_ROOM to lights)

        val unassigned = lights.toMutableList()
        val byName = lights.groupBy { it.name.normalizeRoomKey() }
        val assigned = linkedMapOf<String, MutableList<LightDevice>>()

        rooms.forEach { room ->
            val roomLights = mutableListOf<LightDevice>()
            room.deviceNames.forEach { deviceName ->
                byName[deviceName.normalizeRoomKey()].orEmpty().forEach { light ->
                    if (unassigned.remove(light)) roomLights += light
                }
            }
            if (roomLights.isNotEmpty()) assigned[room.name] = roomLights
        }

        val result = linkedMapOf<String, List<LightDevice>>()
        assigned.forEach { (room, roomLights) -> result[room] = roomLights }
        if (unassigned.isNotEmpty()) result[UNASSIGNED_ROOM] = unassigned
        return result
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
        bindLightCard(row, light)
        addCardToGrid(grid, row.root)
    }

    private fun bindLightCard(row: ItemDeviceBinding, light: LightDevice) {
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
    }

    private fun addLightGroup(
        room: String,
        source: Source,
        roomSourceCount: Int,
        lights: List<LightDevice>,
        grid: GridLayout,
    ) {
        val row = ItemDeviceBinding.inflate(layoutInflater, grid, false)
        row.txtName.text = groupTitle(room, source, roomSourceCount, lights)
        styleLightGroupCard(row, lights)

        var toggleListener: android.widget.CompoundButton.OnCheckedChangeListener? = null
        toggleListener = android.widget.CompoundButton.OnCheckedChangeListener { view, isChecked ->
            view.isEnabled = false
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        lights.forEach { light ->
                            when (light.source) {
                                Source.HUE -> hue.setOn(light.id, isChecked)
                                Source.TUYA -> tuya.setOn(light.id, isChecked)
                            }
                        }
                    }
                }
                if (result.isFailure) {
                    toast("Group toggle failed: ${result.exceptionOrNull()?.message}")
                    row.swToggle.setOnCheckedChangeListener(null)
                    row.swToggle.isChecked = !isChecked
                    row.swToggle.setOnCheckedChangeListener(toggleListener)
                } else {
                    row.txtName.text = groupTitle(room, source, roomSourceCount,
                        lights.map { it.copy(on = isChecked) })
                    styleLightGroupCard(row, lights.map { it.copy(on = isChecked) })
                }
                view.isEnabled = true
            }
        }
        row.swToggle.setOnCheckedChangeListener(null)
        row.swToggle.isChecked = lights.any { it.on }
        row.swToggle.setOnCheckedChangeListener(toggleListener)

        val dimmable = lights.filter { it.supportsBrightness }
        if (dimmable.isNotEmpty()) {
            row.sliderBrightness.visibility = View.VISIBLE
            row.sliderBrightness.value = dimmable
                .mapNotNull { it.brightness }
                .ifEmpty { listOf(1) }
                .average()
                .toInt()
                .coerceIn(1, 100)
                .toFloat()
            row.sliderBrightness.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    val level = slider.value.toInt()
                    slider.isEnabled = false
                    lifecycleScope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                lights.forEach { light ->
                                    when (light.source) {
                                        Source.HUE -> {
                                            if (light.supportsBrightness) hue.setBrightness(light.id, level)
                                            else hue.setOn(light.id, true)
                                        }
                                        Source.TUYA -> {
                                            if (light.supportsBrightness) tuya.setBrightness(light.id, level)
                                            else tuya.setOn(light.id, true)
                                        }
                                    }
                                }
                            }
                        }
                        if (result.isFailure) {
                            toast("Group brightness failed: ${result.exceptionOrNull()?.message}")
                        } else {
                            row.swToggle.setOnCheckedChangeListener(null)
                            row.swToggle.isChecked = true
                            row.swToggle.setOnCheckedChangeListener(toggleListener)
                            row.txtName.text = groupTitle(room, source, roomSourceCount,
                                lights.map { it.copy(on = true, brightness = level) })
                            styleLightGroupCard(row, lights.map { it.copy(on = true) })
                        }
                        slider.isEnabled = true
                    }
                }
            })
        } else {
            row.sliderBrightness.visibility = View.GONE
        }

        row.root.setOnClickListener { showLightGroup(room, source, lights) }
        addGroupCardToGrid(grid, row.root)
    }

    private fun showLightGroup(room: String, source: Source, lights: List<LightDevice>) {
        val grid = GridLayout(this).apply {
            columnCount = 1
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        lights.forEach { light -> addDialogLight(light, grid) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(groupDialogTitle(room, source, lights))
            .setView(grid)
            .setPositiveButton("Done", null)
            .show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(getColor(R.color.portal_surface)))
        dialog.setOnDismissListener { /* no full reload — cards update in place */ }
    }

    private fun styleLightGroupCard(row: ItemDeviceBinding, lights: List<LightDevice>) {
        val isOn = lights.any { it.on }
        row.root.setCardBackgroundColor(
            getColor(if (isOn) R.color.portal_group_surface else R.color.portal_group_surface_off)
        )
        row.root.strokeWidth = dp(1)
        row.root.strokeColor = getColor(if (isOn) R.color.portal_group_outline else R.color.portal_outline)
        row.txtName.setTextColor(getColor(if (isOn) R.color.portal_group_ink else R.color.portal_ink))
    }

    private fun addDialogLight(light: LightDevice, grid: GridLayout) {
        val row = ItemDeviceBinding.inflate(layoutInflater, grid, false)
        bindLightCard(row, light)
        val lp = GridLayout.LayoutParams().apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = dp(126)
            setMargins(dp(6), dp(6), dp(6), dp(6))
        }
        grid.addView(row.root, lp)
    }

    private fun groupTitle(
        room: String,
        source: Source,
        roomSourceCount: Int,
        lights: List<LightDevice>,
    ): String {
        val label = if (roomSourceCount == 1) {
            "$room lights"
        } else {
            "$room ${sourceLabel(source)} lights"
        }
        val onCount = lights.count { it.on }
        val brightness = lights.mapNotNull { it.brightness }.takeIf { it.isNotEmpty() }
            ?.average()?.toInt()
        val status = if (brightness == null) {
            "$onCount On"
        } else {
            "$onCount On • $brightness%"
        }
        return "$label\n$status"
    }

    private fun groupDialogTitle(room: String, source: Source, lights: List<LightDevice>): String {
        return "$room ${sourceLabel(source)} lights (${lights.size})"
    }

    private fun sourceLabel(source: Source): String = when (source) {
        Source.HUE -> "Hue"
        Source.TUYA -> "Feit"
    }

    private fun addSceneCard(
        grid: GridLayout,
        title: String,
        primary: String,
        secondary: String,
        onPrimary: () -> Unit,
        onSecondary: () -> Unit,
    ) {
        val row = ItemSceneBinding.inflate(layoutInflater, grid, false)
        row.txtName.text = title
        row.btnPrimary.text = primary
        row.btnSecondary.text = secondary
        row.btnPrimary.setOnClickListener { onPrimary() }
        row.btnSecondary.setOnClickListener { onSecondary() }
        addCardToGrid(grid, row.root)
    }

    private fun applyToLights(lights: List<LightDevice>, on: Boolean, brightness: Int? = null) {
        binding.txtStatus.text = "Applying scene…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    lights.forEach { light ->
                        when (light.source) {
                            Source.HUE -> {
                                if (brightness != null && light.supportsBrightness) {
                                    hue.setBrightness(light.id, brightness)
                                } else {
                                    hue.setOn(light.id, on)
                                }
                            }
                            Source.TUYA -> {
                                if (brightness != null && light.supportsBrightness) {
                                    tuya.setBrightness(light.id, brightness)
                                } else {
                                    tuya.setOn(light.id, on)
                                }
                            }
                        }
                    }
                }
            }
            result.onFailure { toast("Scene failed: ${it.message}") }
            binding.txtStatus.text = if (result.isSuccess) "Scene applied." else "Updated."
        }
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

    private fun addGroupCardToGrid(grid: GridLayout, view: View) {
        val targetWidth = when {
            resources.configuration.screenWidthDp >= 900 -> dp(440)
            resources.configuration.screenWidthDp >= 640 -> dp(360)
            else -> ViewGroup.LayoutParams.MATCH_PARENT
        }
        val lp = GridLayout.LayoutParams().apply {
            width = targetWidth
            height = dp(148)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
            setMargins(dp(6), dp(8), dp(10), dp(10))
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

    private fun addErrorSection(title: String, error: Throwable) {
        val grid = addSection(title)
        addInfo("Error: ${error.message}", grid)
    }

    private fun String.normalizeRoomKey(): String = trim().lowercase()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val UNASSIGNED_ROOM = "Unassigned"
    }
}
