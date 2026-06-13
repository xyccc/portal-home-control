package com.yvonna.portalhome

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvonna.portalhome.databinding.ActivitySettingsBinding
import com.yvonna.portalhome.databinding.ItemRoomAssignmentBinding
import com.yvonna.portalhome.model.LightDevice
import com.yvonna.portalhome.net.HueClient
import com.yvonna.portalhome.net.TuyaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: Config
    private val roomFields = mutableListOf<Pair<String, EditText>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = Config(this)

        // Prefill from current config.
        binding.etHueBridgeIp.setText(config.hueBridgeIp)
        binding.etHueAppKey.setText(config.hueAppKey)
        binding.etTuyaRegion.setText(config.tuyaRegion)
        binding.etTuyaAccessId.setText(config.tuyaAccessId)
        binding.etTuyaAccessSecret.setText(config.tuyaAccessSecret)
        binding.etTuyaUid.setText(config.tuyaUid)
        binding.etNestProjectId.setText(config.nestProjectId)
        binding.etNestClientId.setText(config.nestClientId)
        binding.etNestClientSecret.setText(config.nestClientSecret)
        binding.etNestRefreshToken.setText(config.nestRefreshToken)
        loadRoomAssignments()

        binding.btnSave.setOnClickListener {
            config.saveAll(
                mapOf(
                    Config.HUE_BRIDGE_IP to binding.etHueBridgeIp.text.toString(),
                    Config.HUE_APP_KEY to binding.etHueAppKey.text.toString(),
                    Config.TUYA_REGION to binding.etTuyaRegion.text.toString(),
                    Config.TUYA_ACCESS_ID to binding.etTuyaAccessId.text.toString(),
                    Config.TUYA_ACCESS_SECRET to binding.etTuyaAccessSecret.text.toString(),
                    Config.TUYA_UID to binding.etTuyaUid.text.toString(),
                    Config.NEST_PROJECT_ID to binding.etNestProjectId.text.toString(),
                    Config.NEST_CLIENT_ID to binding.etNestClientId.text.toString(),
                    Config.NEST_CLIENT_SECRET to binding.etNestClientSecret.text.toString(),
                    Config.NEST_REFRESH_TOKEN to binding.etNestRefreshToken.text.toString(),
                    Config.ROOMS to roomConfigText(),
                )
            )
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadRoomAssignments() {
        roomFields.clear()
        binding.roomAssignmentContainer.removeAllViews()
        binding.txtRoomStatus.text = "Loading devices…"

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val lights = mutableListOf<LightDevice>()
                val errors = mutableListOf<String>()
                if (config.hueConfigured) {
                    runCatching { HueClient(config).listLights() }
                        .onSuccess { lights += it }
                        .onFailure { errors += "Hue: ${it.message}" }
                }
                if (config.tuyaConfigured) {
                    runCatching { TuyaClient(config).listLights() }
                        .onSuccess { lights += it }
                        .onFailure { errors += "Feit: ${it.message}" }
                }
                lights.sortedBy { it.name.lowercase() } to errors
            }

            val (lights, errors) = results
            when {
                lights.isEmpty() && errors.isEmpty() -> {
                    binding.txtRoomStatus.text = "Save device credentials first, then reopen Settings to assign rooms."
                }
                lights.isEmpty() -> {
                    binding.txtRoomStatus.text = errors.joinToString("\n")
                }
                else -> {
                    binding.txtRoomStatus.text = "Choose a room for each device."
                    val roomByDevice = existingRoomAssignments()
                    val roomSuggestions = roomSuggestions(roomByDevice.values)
                    lights.forEach { light ->
                        val row = ItemRoomAssignmentBinding.inflate(
                            layoutInflater,
                            binding.roomAssignmentContainer,
                            false,
                        )
                        row.txtDeviceName.text = light.name
                        row.etRoom.setText(
                            roomByDevice[light.name.normalizeKey()] ?: UNASSIGNED_ROOM,
                            false,
                        )
                        row.etRoom.setAdapter(
                            ArrayAdapter(
                                this@SettingsActivity,
                                android.R.layout.simple_dropdown_item_1line,
                                roomSuggestions,
                            )
                        )
                        row.etRoom.keyListener = null
                        row.etRoom.setOnClickListener { row.etRoom.showDropDown() }
                        row.etRoom.setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) row.etRoom.showDropDown()
                        }
                        binding.roomAssignmentContainer.addView(row.root)
                        roomFields += light.name to row.etRoom
                    }
                    if (errors.isNotEmpty()) {
                        binding.txtRoomStatus.text =
                            "Some devices could not load:\n" + errors.joinToString("\n")
                    }
                }
            }
        }
    }

    private fun existingRoomAssignments(): Map<String, String> {
        val assignments = mutableMapOf<String, String>()
        config.appRooms.forEach { room ->
            room.deviceNames.forEach { deviceName ->
                assignments.putIfAbsent(deviceName.normalizeKey(), room.name)
            }
        }
        return assignments
    }

    private fun roomConfigText(): String {
        if (roomFields.isEmpty()) return config.roomsText
        val rooms = linkedMapOf<String, MutableList<String>>()
        roomFields.forEach { (deviceName, field) ->
            val room = field.text.toString().trim()
            if (room.isNotEmpty() && room != UNASSIGNED_ROOM) {
                rooms.getOrPut(room) { mutableListOf() } += deviceName
            }
        }
        return rooms.entries.joinToString("\n") { (room, devices) ->
            "$room: ${devices.joinToString(", ")}"
        }
    }

    private fun roomSuggestions(existingRooms: Collection<String>): List<String> {
        return (listOf(UNASSIGNED_ROOM) + existingRooms + COMMON_ROOMS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.normalizeKey() }
    }

    private fun String.normalizeKey(): String = trim().lowercase()

    private companion object {
        const val UNASSIGNED_ROOM = "Unassigned"
        val COMMON_ROOMS = listOf(
            "Living Room",
            "Kitchen",
            "Bedroom",
            "Bathroom",
            "Dining Room",
            "Office",
            "Entryway",
            "Hallway",
            "Garage",
            "Patio",
        )
    }
}
