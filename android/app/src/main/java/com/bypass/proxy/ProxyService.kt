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
import java.net.Socket

class ProxyService : Service() {

    private val TAG = "ProxyService"
    private val CHANNEL_ID = "ProxyServiceChannel"
    private val PROXY_PORT = 10808
    private var socksServer: SocksServer? = null
    private var cellularNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        requestCellularNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bypass Proxy Aktif")
            .setContentText("SOCKS5 Proxy $PROXY_PORT portunda dinleniyor")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        if (socksServer == null) {
            // SocksServer başlatılıyor. Ağ bağlayıcı (binder) olarak hücresel veri atanıyor.
            socksServer = SocksServer(
                port = PROXY_PORT,
                networkBinder = { socket ->
                    val net = cellularNetwork
                    if (net != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            net.bindSocket(socket)
                            Log.d(TAG, "Soket hücresel veri arayüzüne (rmnet) başarıyla bağlandı.")
                        }
                    } else {
                        Log.w(TAG, "Hücresel veri ağ arayüzü henüz hazır değil, varsayılan kullanılacak.")
                    }
                },
                logger = { tag, msg -> Log.d(tag, msg) }
            )
            socksServer?.start()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        socksServer?.stop()
        socksServer = null
        Log.d(TAG, "ProxyService sonlandırıldı.")
    }

    private fun requestCellularNetwork() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                Log.d(TAG, "Hücresel ağ başarıyla bağlandı ve kaydedildi: $network")
            }

            override fun onLost(network: Network) {
                if (cellularNetwork == network) {
                    cellularNetwork = null
                    Log.d(TAG, "Hücresel ağ bağlantısı kesildi.")
                }
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bypass Proxy Servisi",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
