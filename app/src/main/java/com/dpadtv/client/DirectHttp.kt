package com.dpadtv.client

import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

/** HttpURLConnection does not follow the observed HTTP-to-HTTPS source redirects automatically. */
internal fun directConnection(address: URL, deadline: Long, userAgent: String, accept: String, referer: String? = null): HttpURLConnection {
    var target = address
    repeat(10) {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining <= 0) throw SocketTimeoutException("请求超时")
        val connection = target.openConnection() as? HttpURLConnection ?: throw ProtocolException("只支持 HTTP 或 HTTPS 地址")
        connection.connectTimeout = minOf(8000, remaining.toInt())
        connection.readTimeout = minOf(8000, remaining.toInt())
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", accept)
        if (referer != null) connection.setRequestProperty("Referer", referer)
        try {
            val code = connection.responseCode
            val location = if (code in listOf(301, 302, 303, 307, 308)) connection.getHeaderField("Location") else null
            if (location == null) return connection
            target = URL(target, location)
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }
        connection.disconnect()
    }
    throw ProtocolException("重定向次数过多")
}
