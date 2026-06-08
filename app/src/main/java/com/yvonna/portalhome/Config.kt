package com.yvonna.portalhome

import android.content.Context
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
    }
}
