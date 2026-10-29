package com.dpadtv.client

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Test-APK-only fixture. Responses are supplied by the probe, never proxied. */
class RegressionHttpServer(private val response: (URI) -> ByteArray) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(8)
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    val base = "http://127.0.0.1:${server.localPort}"
    private val listener = Thread {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (closed: SocketException) {
                if (server.isClosed) break else throw closed
            }
            workers.execute {
                try {
                    socket.use {
                        val input = it.getInputStream().bufferedReader()
                        val line = input.readLine() ?: return@execute
                        val path = URI(line.split(' ')[1])
                        while (!input.readLine().isNullOrEmpty()) Unit
                        counts.computeIfAbsent(path.path) { AtomicInteger() }.incrementAndGet()
                        val body = response(path)
                        it.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(body)
                            flush()
                        }
                    }
                } catch (_: IOException) {
                    // A cancelled production request may close its socket before the reply.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }.apply { isDaemon = true; start() }

    fun count(path: String): Int = counts[path]?.get() ?: 0

    override fun close() {
        server.close()
        listener.join(1_000)
        workers.shutdownNow()
    }
}
