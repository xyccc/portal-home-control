package com.yvonna.portalhome

import android.content.Context
import com.yvonna.portalhome.model.AppRoom
import org.json.JSONArray
import org.json.JSONObject

/**
 * App configuration backed by SharedPreferences.
 *
 * On first launch (empty prefs) it seeds itself from a bundled `assets/config.json`
 * if one is present, so you can ship credentials with the APK. Afterwards the
 * Settings screen is the source of truth.
 */
class Config(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (prefs.all.isEmpty()) seedFromAssets(context)
    }

    private fun seedFromAssets(context: Context) {
        val json = runCatching {
            context.assets.open("config.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return

        val root = JSONObject(json)
        val e = prefs.edit()
        fun copy(section: String, jsonKey: String, prefKey: String) {
            root.optJSONObject(section)?.let { s ->
                if (s.has(jsonKey)) e.putString(prefKey, s.optString(jsonKey))
            }
        }
        copy("hue", "bridge_ip", HUE_BRIDGE_IP)
        copy("hue", "app_key", HUE_APP_KEY)
        copy("tuya", "region_host", TUYA_REGION)
        copy("tuya", "access_id", TUYA_ACCESS_ID)
        copy("tuya", "access_secret", TUYA_ACCESS_SECRET)
        copy("tuya", "uid", TUYA_UID)
        copy("nest", "project_id", NEST_PROJECT_ID)
        copy("nest", "client_id", NEST_CLIENT_ID)
        copy("nest", "client_secret", NEST_CLIENT_SECRET)
        copy("nest", "refresh_token", NEST_REFRESH_TOKEN)
        root.opt("rooms")?.let { rooms ->
            e.putString(ROOMS, formatSeedRooms(rooms))
        }
        e.apply()
    }

    private fun get(key: String): String = prefs.getString(key, "").orEmpty()

    fun saveAll(values: Map<String, String>) {
        val e = prefs.edit()
        values.forEach { (k, v) -> e.putString(k, v.trim()) }
        e.apply()
    }

    val hueBridgeIp get() = get(HUE_BRIDGE_IP)
    val hueAppKey get() = get(HUE_APP_KEY)
    val hueConfigured get() = hueBridgeIp.isNotEmpty() && hueAppKey.isNotEmpty()

    val tuyaRegion get() = get(TUYA_REGION)
    val tuyaAccessId get() = get(TUYA_ACCESS_ID)
    val tuyaAccessSecret get() = get(TUYA_ACCESS_SECRET)
    val tuyaUid get() = get(TUYA_UID)
    val tuyaConfigured
        get() = tuyaRegion.isNotEmpty() && tuyaAccessId.isNotEmpty() &&
            tuyaAccessSecret.isNotEmpty() && tuyaUid.isNotEmpty()

    val nestProjectId get() = get(NEST_PROJECT_ID)
    val nestClientId get() = get(NEST_CLIENT_ID)
    val nestClientSecret get() = get(NEST_CLIENT_SECRET)
    val nestRefreshToken get() = get(NEST_REFRESH_TOKEN)
    val nestConfigured
        get() = nestProjectId.isNotEmpty() && nestClientId.isNotEmpty() &&
            nestClientSecret.isNotEmpty() && nestRefreshToken.isNotEmpty()

    val roomsText get() = get(ROOMS)

    val appRooms: List<AppRoom>
        get() = roomsText.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val parts = trimmed.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val name = parts[0].trim()
            val devices = parts[1].split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (name.isEmpty() || devices.isEmpty()) null else AppRoom(name, devices)
        }

    private fun formatSeedRooms(rooms: Any): String {
        return when (rooms) {
            is JSONObject -> rooms.keys().asSequence().joinToString("\n") { name ->
                val names = rooms.optJSONArray(name)
                val devices = if (names == null) {
                    rooms.optString(name)
                } else {
                    (0 until names.length()).joinToString(", ") { i -> names.optString(i) }
                }
                "$name: $devices"
            }
            is JSONArray -> (0 until rooms.length()).joinToString("\n") { i ->
                val room = rooms.optJSONObject(i) ?: JSONObject()
                val name = room.optString("name")
                val devices = room.optJSONArray("devices") ?: JSONArray()
                "$name: " + (0 until devices.length()).joinToString(", ") { j ->
                    devices.optString(j)
                }
            }
            else -> rooms.toString()
        }
    }

    companion object {
        private const val PREFS = "portalhome"

        const val HUE_BRIDGE_IP = "hue_bridge_ip"
        const val HUE_APP_KEY = "hue_app_key"
        const val TUYA_REGION = "tuya_region"
        const val TUYA_ACCESS_ID = "tuya_access_id"
        const val TUYA_ACCESS_SECRET = "tuya_access_secret"
        const val TUYA_UID = "tuya_uid"
        const val NEST_PROJECT_ID = "nest_project_id"
        const val NEST_CLIENT_ID = "nest_client_id"
        const val NEST_CLIENT_SECRET = "nest_client_secret"
        const val NEST_REFRESH_TOKEN = "nest_refresh_token"
        const val ROOMS = "rooms"
    }
}
