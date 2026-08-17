package com.bypass.proxy

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Android bağımlılıklarından arındırılmış, JVM üzerinde tamamen test edilebilir SOCKS5 Sunucusu.
 * Edge case (uç durum) korumaları ile güçlendirilmiştir.
 */
class SocksServer(
    private val port: Int,
    private val networkBinder: ((Socket) -> Unit)? = null,
    private val logger: (String, String) -> Unit = { tag, msg -> println("[$tag] $msg") }
) {

    private val TAG = "SocksServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket(port)
                logger(TAG, "SOCKS5 Sunucusu başlatıldı. Port: $port")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    threadPool.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: IOException) {
                logger(TAG, "Sunucu soket hatası: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            logger(TAG, "Soket kapatılamadı: ${e.message}")
        }
        threadPool.shutdownNow()
        logger(TAG, "SOCKS5 Sunucusu durduruldu.")
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val clientInput = clientSocket.getInputStream()
            val clientOutput = clientSocket.getOutputStream()

            // 1. Aşama: El Sıkışma (Handshake)
            val version = clientInput.read()
            if (version == -1) return // Edge Case: İstemci erken bağ kopardı
            
            if (version != 5) {
                logger(TAG, "Desteklenmeyen SOCKS sürümü: $version")
                return // Bağlantıyı hemen kes, SOCKS5 dışı isteklere yanıt verme
            }

            val nMethods = clientInput.read()
            if (nMethods <= 0) return
            
            val methods = ByteArray(nMethods)
            val bytesRead = clientInput.read(methods)
            if (bytesRead != nMethods) return

            // No Authentication (0x00) yanıtı dön
            clientOutput.write(byteArrayOf(5, 0))
            clientOutput.flush()

            // 2. Aşama: İstemci İsteği (Request)
            val reqVer = clientInput.read()
            val cmd = clientInput.read()
            clientInput.read() // RSV
            val atyp = clientInput.read()

            if (reqVer == -1 || cmd == -1) return

            // Edge Case: Desteklenmeyen komut (Sadece CONNECT=0x01 desteklenir, BIND=0x02 desteklenmez)
            if (cmd != 1) {
                logger(TAG, "Desteklenmeyen komut veya sürüm: CMD=$cmd")
                sendErrorResponse(clientOutput, 0x07) // 0x07: Command not supported
                return
            }

            val host: String
            when (atyp) {
                1 -> { // IPv4
                    val ipv4 = ByteArray(4)
                    if (clientInput.read(ipv4) != 4) return
                    host = InetAddress.getByAddress(ipv4).hostAddress
                }
                3 -> { // Domain Name
                    val len = clientInput.read()
                    if (len <= 0) {
                        sendErrorResponse(clientOutput, 0x08)
                        return
                    }
                    val domainBytes = ByteArray(len)
                    if (clientInput.read(domainBytes) != len) return
                    host = String(domainBytes)
                }
                4 -> { // IPv6
                    val ipv6 = ByteArray(16)
                    if (clientInput.read(ipv6) != 16) return
                    host = InetAddress.getByAddress(ipv6).hostAddress
                }
                else -> {
                    // Edge Case: Desteklenmeyen adres tipi
                    sendErrorResponse(clientOutput, 0x08) // 0x08: Address type not supported
                    return
                }
            }

            val portHigh = clientInput.read()
            val portLow = clientInput.read()
            if (portHigh == -1 || portLow == -1) return
            val port = (portHigh shl 8) or portLow

            logger(TAG, "Bağlantı talebi: $host:$port")

            val remoteSocket = Socket()
            networkBinder?.invoke(remoteSocket)

            try {
                // Edge Case: Zaman aşımı ile bağlantı kurma
                remoteSocket.connect(java.net.InetSocketAddress(host, port), 10000)
            } catch (e: ConnectException) {
                logger(TAG, "Bağlantı reddedildi: $host:$port")
                sendErrorResponse(clientOutput, 0x05) // 0x05: Connection refused
                return
            } catch (e: Exception) {
                logger(TAG, "Hedefe bağlanılamadı: $host:$port. Hata: ${e.message}")
                sendErrorResponse(clientOutput, 0x04) // 0x04: Host unreachable
                return
            }

            // Başarılı bağlantı yanıtını gönder (0x00 Success)
            val successResponse = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)
            clientOutput.write(successResponse)
            clientOutput.flush()

            // 3. Aşama: Çift yönlü veri aktarımı (Relay)
            val remoteInput = remoteSocket.getInputStream()
            val remoteOutput = remoteSocket.getOutputStream()

            val forwardThread = thread(start = true) {
                relayData(clientInput, remoteOutput)
                try { remoteSocket.shutdownOutput() } catch (e: Exception) {}
            }

            relayData(remoteInput, clientOutput)
            try { clientSocket.shutdownOutput() } catch (e: Exception) {}

            forwardThread.join()

        } catch (e: Exception) {
            logger(TAG, "Soket iletişim hatası: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    private fun relayData(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16384)
        var bytesRead: Int
        try {
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: IOException) {
            // Soket kapandığında / İletişim koptuğunda normal çıkış
        }
    }

    private fun sendErrorResponse(output: OutputStream, repCode: Byte) {
        try {
            output.write(byteArrayOf(5, repCode, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
        } catch (e: Exception) {}
    }
}
