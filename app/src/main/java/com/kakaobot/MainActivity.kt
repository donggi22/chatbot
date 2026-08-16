package com.kakaobot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var config: BotConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = BotConfig(this)

        val etServerUrl = findViewById<EditText>(R.id.etServerUrl)
        val etTrigger   = findViewById<EditText>(R.id.etTrigger)
        val etRooms     = findViewById<EditText>(R.id.etRooms)
        val swEnabled   = findViewById<SwitchMaterial>(R.id.swEnabled)
        val btnSave     = findViewById<Button>(R.id.btnSave)
        val btnPerm     = findViewById<Button>(R.id.btnPermission)
        val tvLogs      = findViewById<TextView>(R.id.tvLogs)

        // 현재 설정값 로드
        etServerUrl.setText(config.serverUrl)
        etTrigger.setText(config.triggerWord)
        etRooms.setText(config.allowedRooms)
        swEnabled.isChecked = config.isEnabled

        btnSave.setOnClickListener {
            config.serverUrl   = etServerUrl.text.toString().trimEnd('/')
            config.triggerWord = etTrigger.text.toString()
            config.allowedRooms = etRooms.text.toString()
            config.isEnabled   = swEnabled.isChecked
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        }

        // 알림 접근 권한 설정 화면으로 이동
        btnPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // 실시간 로그 스크롤
        (application as KakaoBotApp).logs.observe(this) { logs ->
            val text = logs.takeLast(80).joinToString("\n")
            tvLogs.text = text
            tvLogs.post { (tvLogs.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
