package com.bypass.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class SocksServerTest {

    private val PROXY_PORT = 10888
    private val ECHO_PORT = 9999
    private var proxyServer: SocksServer? = null
    private var echoServerSocket: ServerSocket? = null
    private var isEchoRunning = true

    @Before
    fun setUp() {
        // 1. SOCKS5 Sunucusunu Yerelde Başlatıyoruz
        proxyServer = SocksServer(
            port = PROXY_PORT,
            networkBinder = null, // Test için yerel ağda çalıştığımızdan Android bind mekanizmasına ihtiyacımız yok
            logger = { tag, msg -> println("[$tag] $msg") }
        )
        proxyServer?.start()

        // 2. Bir adet basit "Echo" (Yankı) sunucusu başlatıyoruz (Tünelin ucu buraya çıkacak)
        isEchoRunning = true
        echoServerSocket = ServerSocket(ECHO_PORT)
        thread {
            try {
                while (isEchoRunning) {
                    val socket = echoServerSocket?.accept() ?: break
                    thread {
                        try {
                            val input = socket.getInputStream()
                            val output = socket.getOutputStream()
                            val buffer = ByteArray(1024)
                            var len: Int
                            while (input.read(buffer).also { len = it } != -1) {
                                output.write(buffer, 0, len)
                                output.flush()
                            }
                        } catch (e: Exception) {
                            // Bağlantı kapatıldı
                        } finally {
                            socket.close()
                        }
                    }
                }
            } catch (e: Exception) {
                // Sunucu kapandı
            }
        }
        
        // Sunucuların ayaklanması için kısa bir bekleme süresi
        Thread.sleep(200)
    }

    @After
    fun tearDown() {
        proxyServer?.stop()
        isEchoRunning = false
        echoServerSocket?.close()
    }

    @Test
    fun testSocks5HandshakeAndDataRelay() {
        // İstemci SOCKS5 proxy sunucusuna bağlanır
        val clientSocket = Socket("127.0.0.1", PROXY_PORT)
        val input = clientSocket.getInputStream()
        val output = clientSocket.getOutputStream()

        // --- 1. AŞAMA: EL SIKIŞMA (Handshake) ---
        // SOCKS5, No Authentication Required (Metot sayısı 1, Metot 0x00)
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()

        // Sunucunun yanıtını kontrol et: [VER: 5, CHOSEN_METHOD: 0]
        val hsResponse = ByteArray(2)
        input.read(hsResponse)
        assertEquals(0x05.toByte(), hsResponse[0])
        assertEquals(0x00.toByte(), hsResponse[1])
        println("SOCKS5 El sıkışması (Handshake) başarılı!")

        // --- 2. AŞAMA: BAĞLANTI İSTEĞİ (Request) ---
        // CONNECT komutu, IPv4 hedef (127.0.0.1), Echo Portu (ECHO_PORT)
        val request = ByteArray(10)
        request[0] = 0x05 // Sürüm
        request[1] = 0x01 // CMD: CONNECT (0x01)
        request[2] = 0x00 // RSV: Sabit 0
        request[3] = 0x01 // ATYP: IPv4 (0x01)
        
        // 127.0.0.1 IP'si byte dizisine dönüştürülüyor
        request[4] = 127
        request[5] = 0
        request[6] = 0
        request[7] = 1

        // Port (ECHO_PORT) Big-Endian olarak yazılıyor
        request[8] = ((ECHO_PORT shr 8) and 0xFF).toByte()
        request[9] = (ECHO_PORT and 0xFF).toByte()

        output.write(request)
        output.flush()

        // Sunucudan gelen yanıtı oku (10 byte uzunluğunda başarı paketi bekliyoruz)
        val connResponse = ByteArray(10)
        input.read(connResponse)
        
        assertEquals(0x05.toByte(), connResponse[0]) // Sürüm 5
        assertEquals(0x00.toByte(), connResponse[1]) // REP: 0x00 (Success)
        println("SOCKS5 Hedefe tünel bağlantısı sağlandı!")

        // --- 3. AŞAMA: VERİ İLETİM TESTİ (Data Relay) ---
        // Tünel üzerinden "Merhaba SOCKS5!" mesajı gönderip Echo sunucusundan geri alabiliyor muyuz?
        val testMessage = "Merhaba SOCKS5!"
        output.write(testMessage.toByteArray())
        output.flush()

        val responseBuffer = ByteArray(1024)
        val bytesRead = input.read(responseBuffer)
        val receivedMessage = String(responseBuffer, 0, bytesRead)

        assertEquals(testMessage, receivedMessage)
        println("Doğrulandı! Gönderilen mesaj: '$testMessage', Geri gelen: '$receivedMessage'")

        clientSocket.close()
    }
}
