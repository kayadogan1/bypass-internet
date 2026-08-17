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
        proxyServer = SocksServer(
            port = PROXY_PORT,
            networkBinder = null,
            logger = { tag, msg -> println("[$tag] $msg") }
        )
        proxyServer?.start()

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
                        } catch (e: Exception) { } 
                        finally { socket.close() }
                    }
                }
            } catch (e: Exception) { }
        }
        Thread.sleep(200)
    }

    @After
    fun tearDown() {
        proxyServer?.stop()
        isEchoRunning = false
        echoServerSocket?.close()
    }

    private fun connectClientAndHandshake(): Pair<Socket, Pair<InputStream, OutputStream>> {
        val socket = Socket("127.0.0.1", PROXY_PORT)
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()

        val hsResponse = ByteArray(2)
        input.read(hsResponse)
        assertEquals(0x05.toByte(), hsResponse[0])
        assertEquals(0x00.toByte(), hsResponse[1])
        
        return Pair(socket, Pair(input, output))
    }

    @Test
    fun testSocks5UnsupportedVersion() {
        val socket = Socket("127.0.0.1", PROXY_PORT)
        val output = socket.getOutputStream()
        
        // SOCKS4 isteği gönder (0x04)
        output.write(byteArrayOf(0x04, 0x01, 0x00))
        output.flush()

        val input = socket.getInputStream()
        val response = input.read()
        // Sunucu anında bağlantıyı kestiği için -1 döner
        assertEquals(-1, response)
        socket.close()
    }

    @Test
    fun testSocks5UnsupportedCommand() {
        val (socket, streams) = connectClientAndHandshake()
        val (input, output) = streams

        // CMD: 0x02 (BIND - Desteklenmez)
        val request = byteArrayOf(0x05, 0x02, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x50)
        output.write(request)
        output.flush()

        val response = ByteArray(10)
        input.read(response)
        
        // Hata Kodu 0x07: Command Not Supported dönmeli
        assertEquals(0x05.toByte(), response[0])
        assertEquals(0x07.toByte(), response[1])
        
        socket.close()
    }

    @Test
    fun testSocks5HostUnreachable() {
        val (socket, streams) = connectClientAndHandshake()
        val (input, output) = streams

        // Ulaşılamayan rastgele bir porta (8888) CONNECT at
        val dummyPort = 8888
        val portHigh = ((dummyPort shr 8) and 0xFF).toByte()
        val portLow = (dummyPort and 0xFF).toByte()
        val request = byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, portHigh, portLow)
        
        output.write(request)
        output.flush()

        val response = ByteArray(10)
        input.read(response)
        
        // Hata Kodu 0x05 (Connection Refused) dönmeli
        assertEquals(0x05.toByte(), response[0])
        assertEquals(0x05.toByte(), response[1])
        
        socket.close()
    }

    @Test
    fun testSocks5DomainNameAddressParsing() {
        val (socket, streams) = connectClientAndHandshake()
        val (input, output) = streams

        val domain = "localhost"
        val domainBytes = domain.toByteArray()
        val request = ByteArray(4 + 1 + domainBytes.size + 2)
        request[0] = 0x05 // VER
        request[1] = 0x01 // CMD
        request[2] = 0x00 // RSV
        request[3] = 0x03 // ATYP: Domain (0x03)
        request[4] = domainBytes.size.toByte() // Domain Length
        System.arraycopy(domainBytes, 0, request, 5, domainBytes.size)

        val portIndex = 5 + domainBytes.size
        request[portIndex] = ((ECHO_PORT shr 8) and 0xFF).toByte()
        request[portIndex + 1] = (ECHO_PORT and 0xFF).toByte()

        output.write(request)
        output.flush()

        val response = ByteArray(10)
        input.read(response)
        
        // Domain başarılı çözüldüğü ve bağlandığı için 0x00 (Success) dönmeli
        assertEquals(0x05.toByte(), response[0])
        assertEquals(0x00.toByte(), response[1])

        socket.close()
    }

    @Test
    fun testSocks5UnsupportedAddressType() {
        val (socket, streams) = connectClientAndHandshake()
        val (input, output) = streams

        // ATYP: 0x05 (Geçersiz adres tipi)
        val request = byteArrayOf(0x05, 0x01, 0x00, 0x05, 127, 0, 0, 1, 0x00, 0x50)
        output.write(request)
        output.flush()

        val response = ByteArray(10)
        input.read(response)
        
        // Hata Kodu 0x08: Address Type Not Supported dönmeli
        assertEquals(0x05.toByte(), response[0])
        assertEquals(0x08.toByte(), response[1])

        socket.close()
    }
}
