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
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class ProxyService : Service() {

    private val TAG = "ProxyService"
    private val CHANNEL_ID = "ProxyServiceChannel"
    private val PROXY_PORT = 10808
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var cellularNetwork: Network? = null
    
    // Yüksek throughput ve performans için thread havuzu (Thread Pool)
    private val threadPool = Executors.newCachedThreadPool()

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
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Server socket kapatılırken hata oluştu: ${e.message}")
        }
        threadPool.shutdownNow()
        Log.d(TAG, "Servis durduruldu, havuz boşaltıldı.")
    }

    /**
     * Hücresel veri (rmnet_data) arayüzünü tespit eder ve kaydeder.
     * Bu sayede tüm uzak bağlantı soketlerimizi bu arayüze bağlayacağız (bindSocket).
     */
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

    private fun startProxyServer() {
        isRunning = true
        thread {
            try {
                // Hotspot veya USB tethering ağlarından erişim için 0.0.0.0 arayüzüne bağlanıyoruz
                serverSocket = ServerSocket(PROXY_PORT)
                Log.i(TAG, "SOCKS5 Sunucusu başlatıldı: 0.0.0.0:$PROXY_PORT")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    // Her istemciyi yüksek performans için thread havuzuna gönderiyoruz
                    threadPool.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Sunucu soket hatası: ${e.message}")
            }
        }
    }

    /**
     * RFC 1928 SOCKS5 Protokol El Sıkışması ve Veri İletimi
     */
    private fun handleClient(clientSocket: Socket) {
        try {
            val clientInput = clientSocket.getInputStream()
            val clientOutput = clientSocket.getOutputStream()

            // 1. Aşama: El Sıkışma (Handshake) Giriş Paketi
            // [VER (1 byte), NMETHODS (1 byte), METHODS (1-255 bytes)]
            val version = clientInput.read()
            if (version != 5) {
                Log.w(TAG, "Desteklenmeyen SOCKS sürümü: $version")
                clientSocket.close()
                return
            }

            val nMethods = clientInput.read()
            val methods = ByteArray(nMethods)
            clientInput.read(methods)

            // Yanıt: No Authentication Required (0x00) seçildiğini bildiriyoruz
            // [VER (1 byte), METHOD (1 byte)]
            clientOutput.write(byteArrayOf(5, 0))
            clientOutput.flush()

            // 2. Aşama: İstemci İsteği (Request)
            // [VER (1 byte), CMD (1 byte), RSV (1 byte), ATYP (1 byte), DST.ADDR, DST.PORT (2 bytes)]
            val reqVer = clientInput.read()
            val cmd = clientInput.read()
            clientInput.read() // RSV (Rezerv byte, atlanıyor)
            val atyp = clientInput.read()

            if (reqVer != 5 || cmd != 1) { // Sadece CONNECT (0x01) komutunu destekliyoruz
                Log.w(TAG, "Desteklenmeyen komut veya sürüm: CMD=$cmd")
                sendErrorResponse(clientOutput, 0x07) // Command not supported
                clientSocket.close()
                return
            }

            // Hedef adresi çözümleme
            val host: String
            when (atyp) {
                1 -> { // IPv4 (4 bytes)
                    val ipv4 = ByteArray(4)
                    clientInput.read(ipv4)
                    host = InetAddress.getByAddress(ipv4).hostAddress
                }
                3 -> { // Domain adı (İlk byte uzunluğu belirtir)
                    val len = clientInput.read()
                    val domainBytes = ByteArray(len)
                    clientInput.read(domainBytes)
                    host = String(domainBytes)
                }
                4 -> { // IPv6 (16 bytes)
                    val ipv6 = ByteArray(16)
                    clientInput.read(ipv6)
                    host = InetAddress.getByAddress(ipv6).hostAddress
                }
                else -> {
                    sendErrorResponse(clientOutput, 0x08) // Address type not supported
                    clientSocket.close()
                    return
                }
            }

            // Port bilgisi (2 bytes, Big-Endian)
            val portHigh = clientInput.read()
            val portLow = clientInput.read()
            val port = (portHigh shl 8) or portLow

            Log.d(TAG, "Bağlantı isteği hedefi -> $host:$port")

            // Uzak sunucuya bağlantıyı gerçekleştirme
            val remoteSocket = Socket()
            
            // Kritik Aşama: Soketi hücresel ağa (rmnet) bağlama (Tethering APN/Limit atlatması)
            val net = cellularNetwork
            if (net != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    net.bindSocket(remoteSocket)
                    Log.d(TAG, "$host:$port bağlantısı hücresel veri arayüzüne bağlandı.")
                }
            } else {
                Log.w(TAG, "Hücresel ağ bulunamadı! Varsayılan sistem arayüzü kullanılacak.")
            }

            try {
                // Bağlantıyı başlat (Zaman aşımı: 10 saniye)
                remoteSocket.connect(java.net.InetSocketAddress(host, port), 10000)
            } catch (e: Exception) {
                Log.e(TAG, "Hedef sunucuya bağlanılamadı: $host:$port. Hata: ${e.message}")
                sendErrorResponse(clientOutput, 0x04) // Host unreachable
                clientSocket.close()
                return
            }

            // Bağlantı başarılı yanıtını gönderiyoruz
            // [VER (5), REP (0 = Success), RSV (0), ATYP (1), BND.ADDR (0.0.0.0), BND.PORT (0)]
            val successResponse = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)
            clientOutput.write(successResponse)
            clientOutput.flush()

            // 3. Aşama: Çift yönlü veri akışını (Relaying) başlatma
            val remoteInput = remoteSocket.getInputStream()
            val remoteOutput = remoteSocket.getOutputStream()

            // İstemciden -> Uzak Sunucuya
            val forwardThread = thread(start = true) {
                relayData(clientInput, remoteOutput)
                try { remoteSocket.shutdownOutput() } catch (e: Exception) {}
            }

            // Uzak Sunucudan -> İstemciye
            relayData(remoteInput, clientOutput)
            try { clientSocket.shutdownOutput() } catch (e: Exception) {}

            forwardThread.join()

        } catch (e: Exception) {
            Log.e(TAG, "Soket iletişiminde hata: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    /**
     * İki akış (stream) arasında ham veriyi yüksek performanslı tampon (buffer) ile aktarır.
     */
    private fun relayData(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16384) // 16KB tampon ile yüksek throughput
        var bytesRead: Int
        try {
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: IOException) {
            // Soket kapandığında normal bir şekilde döngüden çıkılır
        }
    }

    private fun sendErrorResponse(output: OutputStream, repCode: Byte) {
        try {
            output.write(byteArrayOf(5, repCode, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
        } catch (e: Exception) {}
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
