package ru.souz.backend.plugins

import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/** Real PostgreSQL traffic; only server->client delivery is paused after a selected SQL query. */
internal class PgResponseGate(jdbcUrl: String, private val pauseAll: Boolean = true) : AutoCloseable {
    private val upstream = URI(jdbcUrl.removePrefix("jdbc:"))
    private val listener = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "plugin-test-pg-proxy").apply { isDaemon = true } }
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val connectionGates = CopyOnWriteArrayList<AtomicBoolean>()
    private val monitor = Object()
    private val closed = AtomicBoolean()
    @Volatile private var paused = false
    @Volatile private var match: String? = null
    val entered = CompletableDeferred<Unit>()
    val url = (if (jdbcUrl.startsWith("jdbc:")) "jdbc:" else "") + URI(upstream.scheme, null, "127.0.0.1", listener.localPort, upstream.path, upstream.query, null)

    init {
        executor.submit {
            while (!closed.get()) try {
                val client = listener.accept().also { it.tcpNoDelay = true; sockets += it }
                val server = Socket(upstream.host, upstream.port).also { it.tcpNoDelay = true; sockets += it }
                val connectionGate = AtomicBoolean().also(connectionGates::add)
                val outcomeMarker = AtomicBoolean()
                pump(client, server, requests = true, connectionGate, outcomeMarker)
                pump(server, client, requests = false, connectionGate, outcomeMarker)
            } catch (_: java.io.IOException) { if (!closed.get()) throw IllegalStateException("Proxy accept failed") }
        }
    }
    fun arm(sql: String) { check(match == null); match = sql.lowercase() }
    fun resume() = synchronized(monitor) { paused = false; match = null; connectionGates.forEach { it.set(false) }; monitor.notifyAll() }

    private fun pump(from: Socket, to: Socket, requests: Boolean, connectionGate: AtomicBoolean, outcomeMarker: AtomicBoolean) {
        executor.submit {
            var recent = ""
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buffer = ByteArray(16384)
                while (!closed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (requests) {
                        recent = (recent + String(buffer, 0, count, Charsets.ISO_8859_1)).takeLast(16384)
                        val pattern = match
                        val text = recent.lowercase().replace(Regex("\\s+"), " ")
                        val trigger = if (pattern == "commit after recorded") {
                            if (text.contains("set recorded = true")) outcomeMarker.set(true)
                            false // PostgreSQL always names COMMIT in its server CommandComplete response, even for a cached prepared command.
                        } else pattern != null && text.contains(pattern)
                        if (pattern != null && !entered.isCompleted && trigger) {
                            synchronized(monitor) { if (pauseAll) paused = true else connectionGate.set(true); entered.complete(Unit) }
                        }
                    } else {
                        if (match == "commit after recorded" && outcomeMarker.get() && !entered.isCompleted) {
                            recent = (recent + String(buffer,0,count,Charsets.ISO_8859_1)).takeLast(2048)
                            if (recent.contains("COMMIT")) synchronized(monitor) {
                                if (pauseAll) paused = true else connectionGate.set(true)
                                entered.complete(Unit)
                            }
                        }
                        synchronized(monitor) { while ((paused || connectionGate.get()) && !closed.get()) monitor.wait() }
                    }
                    output.write(buffer, 0, count)
                    output.flush()
                }
            } catch (_: Exception) {
                // Controlled disconnect wakes JDBC reads in cleanup.
            } finally {
                runCatching { from.close() }; runCatching { to.close() }
            }
        }
    }
    override fun close() {
        closed.set(true)
        resume()
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }
}
