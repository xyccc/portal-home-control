package com.yvonna.portalhome

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yvonna.portalhome.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: Config

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
        binding.etRooms.setText(config.roomsText)

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
                    Config.ROOMS to binding.etRooms.text.toString(),
                )
            )
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
