package com.bypass.proxy

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Basit bir arayüz oluşturalım
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }
        
        val btnStart = Button(this).apply {
            text = "Proxy Servisini Başlat"
            setOnClickListener { startProxy() }
        }
        
        val btnStop = Button(this).apply {
            text = "Proxy Servisini Durdur"
            setOnClickListener { stopProxy() }
        }

        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)
    }

    private fun startProxy() {
        val intent = Intent(this, ProxyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Servis başlatılıyor...", Toast.LENGTH_SHORT).show()
    }

    private fun stopProxy() {
        val intent = Intent(this, ProxyService::class.java)
        stopService(intent)
        Toast.makeText(this, "Servis durduruldu.", Toast.LENGTH_SHORT).show()
    }
}
