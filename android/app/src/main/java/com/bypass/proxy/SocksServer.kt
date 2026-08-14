package com.bypass.proxy

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Android bağımlılıklarından arındırılmış, JVM üzerinde tamamen test edilebilir SOCKS5 Sunucusu.
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

            // 1. Aşama: El Sıkışma
            val version = clientInput.read()
            if (version != 5) {
                logger(TAG, "Desteklenmeyen SOCKS sürümü: $version")
                clientSocket.close()
                return
            }

            val nMethods = clientInput.read()
            val methods = ByteArray(nMethods)
            clientInput.read(methods)

            clientOutput.write(byteArrayOf(5, 0))
            clientOutput.flush()

            // 2. Aşama: İstemci İsteği (Request)
            val reqVer = clientInput.read()
            val cmd = clientInput.read()
            clientInput.read() // RSV
            val atyp = clientInput.read()

            if (reqVer != 5 || cmd != 1) { // Sadece CONNECT destekleniyor
                logger(TAG, "Desteklenmeyen komut veya sürüm: CMD=$cmd")
                sendErrorResponse(clientOutput, 0x07)
                clientSocket.close()
                return
            }

            val host: String
            when (atyp) {
                1 -> { // IPv4
                    val ipv4 = ByteArray(4)
                    clientInput.read(ipv4)
                    host = InetAddress.getByAddress(ipv4).hostAddress
                }
                3 -> { // Domain
                    val len = clientInput.read()
                    val domainBytes = ByteArray(len)
                    clientInput.read(domainBytes)
                    host = String(domainBytes)
                }
                4 -> { // IPv6
                    val ipv6 = ByteArray(16)
                    clientInput.read(ipv6)
                    host = InetAddress.getByAddress(ipv6).hostAddress
                }
                else -> {
                    sendErrorResponse(clientOutput, 0x08)
                    clientSocket.close()
                    return
                }
            }

            val portHigh = clientInput.read()
            val portLow = clientInput.read()
            val port = (portHigh shl 8) or portLow

            logger(TAG, "Bağlantı talebi: $host:$port")

            val remoteSocket = Socket()
            
            // Eğer Android tarafı bir ağ bağlayıcı (network binder) sağladıysa soketi o arayüze bağla
            networkBinder?.invoke(remoteSocket)

            try {
                remoteSocket.connect(java.net.InetSocketAddress(host, port), 10000)
            } catch (e: Exception) {
                logger(TAG, "Hedefe bağlanılamadı: $host:$port. Hata: ${e.message}")
                sendErrorResponse(clientOutput, 0x04)
                clientSocket.close()
                return
            }

            // Başarılı yanıtı gönder
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
            // Soket kapandığında normal çıkış
        }
    }

    private fun sendErrorResponse(output: OutputStream, repCode: Byte) {
        try {
            output.write(byteArrayOf(5, repCode, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
        } catch (e: Exception) {}
    }
}
