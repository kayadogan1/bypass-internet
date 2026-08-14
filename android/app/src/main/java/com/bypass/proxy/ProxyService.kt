package com.bypass.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ProxyService : Service() {

    private val CHANNEL_ID = "ProxyServiceChannel"
    private val PROXY_PORT = 10808
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var cellularNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        requestCellularNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bypass Proxy Çalışıyor")
            .setContentText("SOCKS5 Proxy $PROXY_PORT portunda dinleniyor")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .build()

        startForeground(1, notification)

        if (!isRunning) {
            startProxyServer()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serverSocket?.close()
        Log.d("ProxyService", "Servis durduruldu")
    }

    // Hücresel veri (rmnet_data) arayüzünü zorunlu kılmak için NetworkRequest kullanıyoruz.
    // Bu sayede yerel ağ (Wi-Fi Hotspot) üzerinden gelen trafik, hücresel veri arayüzünden dışarı çıkar.
    private fun requestCellularNetwork() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                Log.d("ProxyService", "Hücresel ağ bağlandı: $network")
                // İsteğe bağlı: uygulamanın tüm dışarı giden trafiğini bu ağa bağlar.
                // connectivityManager.bindProcessToNetwork(network)
            }
        })
    }

    private fun startProxyServer() {
        isRunning = true
        thread {
            try {
                // Sadece Hotspot veya USB Tethering'den gelenlere hizmet verebilmek için 0.0.0.0
                serverSocket = ServerSocket(PROXY_PORT)
                Log.d("ProxyService", "SOCKS5 Sunucusu başlatıldı: 0.0.0.0:$PROXY_PORT")

                while (isRunning) {
                    val clientSocket = serverSocket!!.accept()
                    handleClient(clientSocket)
                }
            } catch (e: IOException) {
                Log.e("ProxyService", "Sunucu hatası: ${e.message}")
            }
        }
    }

    // Basit bir SOCKS5 handshake ve port yönlendirme mantığı (Örnek/Boilerplate)
    // Gerçek bir senaryoda bu kısmı Netty, Go-tun2socks veya Xray-core (gomobile) gibi stabil bir kütüphane devralır.
    private fun handleClient(clientSocket: Socket) {
        thread {
            try {
                // TODO: SOCKS5 handshake (0x05) uygulayın veya dış bir kütüphane kullanın.
                // cellularNetwork?.bindSocket(clientSocket) ile dışarı giden bağlantıyı hücresel veriye zorlayabilirsiniz.
                Log.d("ProxyService", "Yeni istemci bağlandı: ${clientSocket.inetAddress.hostAddress}")
                
                // İstemci işlemleri sonrası soketi kapatın...
                // clientSocket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Proxy Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
