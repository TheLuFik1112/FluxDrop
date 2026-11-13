package app.fluxdrop.net

import app.fluxdrop.core.Diag
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Ktor 3 CIO не умеет HTTPS, поэтому TLS-терминация вынесена в отдельный слой:
 * приложение слушает настоящий TLS-порт, расшифровывает поток и передаёт его
 * на loopback-порт, где работает обычный HTTP-сервер Ktor.
 */
class TlsRelay(
    private val sslContext: SSLContext,
    private val listenPort: Int,
    private val localPort: Int,
) {

    private var serverSocket: SSLServerSocket? = null
    private var acceptor: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val socket = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(listenPort), 50)
        serverSocket = socket
        running = true

        acceptor = Thread({
            Diag.log("relay", "TLS listening on $listenPort -> 127.0.0.1:$localPort")
            while (running) {
                val client = try {
                    socket.accept()
                } catch (error: Exception) {
                    if (running) Diag.log("relay", "accept failed: $error")
                    break
                }
                Thread({ handle(client) }, "fluxdrop-tls-conn").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "fluxdrop-tls").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptor?.interrupt() }
        acceptor = null
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var step = "begin"
        try {
            step = "inspect"
            Diag.log(
                "relay",
                "conn in: local=$localPort remote=${client.inetAddress?.hostAddress}:${client.port} " +
                    "connected=${client.isConnected} ssl=${client is SSLSocket}",
            )

            step = "handshake"
            (client as? SSLSocket)?.let { ssl ->
                ssl.useClientMode = false
                ssl.startHandshake()
            }
            Diag.log("relay", "handshake ok")

            step = "connect upstream"
            upstream = Socket().apply {
                connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
                tcpNoDelay = true
            }
            Diag.log("relay", "upstream connected")

            val target = upstream
            step = "pump"
            Thread({ pump(client, target) }, "fluxdrop-tls-up").apply {
                isDaemon = true
                start()
            }
            pump(target, client)
        } catch (error: Exception) {
            Diag.log("relay", "failed at $step: ${error.stackTraceToString()}")
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val input: InputStream = from.getInputStream()
            val output: OutputStream = to.getOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
            runCatching { to.shutdownOutput() }
        } catch (_: Exception) {
            // соединение закрылось — обычная ситуация
        }
    }
}