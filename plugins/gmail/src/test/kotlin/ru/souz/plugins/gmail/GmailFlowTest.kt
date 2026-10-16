package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import ru.souz.plugins.PluginOAuthResponse

class GmailFlowTest {
    @Test fun `baseline precedes history and watch never replaces cursor`() = runTest {
        val host = GmailTestHost()
        host.configure()
        val claim = host.claim()
        assertTrue(host.requests[0].url.endsWith("/profile"))
        assertContains(host.requests[1].url, "startHistoryId=100")
        val read = host.call("read", "config" to "personal", "message" to "m1", "claim" to claim["claim"].asText())
        assertEquals("compose", read["status"].asText())
        assertEquals("Plain email", read["email"]["body"].asText())
        assertTrue(read["email"]["untrusted"].asBoolean())
        assertEquals("owned", read["inputs"]["channelId"].asText())
        assertEquals("watching", host.call("watch", "config" to "personal")["status"].asText())
        assertEquals("101", host.state()["historyCursor"].asText())
        assertContains(host.requests.last().body.toString(Charsets.UTF_8), "labelFilterBehavior")
    }

    @Test fun `pagination keeps its base cursor and stale fetch conflicts`() = runTest {
        val host = GmailTestHost()
        host.configure()
        val normal = host.respond
        host.respond = { request ->
            if ("/history?" in request.url) PluginOAuthResponse(200,
                if ("pageToken" !in request.url) """{"historyId":"999","nextPageToken":"page one","history":[]}"""
                else """{"historyId":"999","history":[]}""") else normal(request)
        }
        assertTrue(host.call("sync", "config" to "personal")["moreHistory"].asBoolean())
        assertEquals("100", host.state()["historyCursor"].asText())
        host.call("sync", "config" to "personal")
        assertContains(host.requests.last().url, "pageToken=page+one")
        assertContains(host.requests.last().url, "startHistoryId=100")
        assertEquals("999", host.state()["historyCursor"].asText())
        host.respond = { request ->
            if ("/history?" in request.url) {
                host.data.update("connection:personal") { stored ->
                    (stored!!["state"] as ObjectNode).put("historyCursor", "1000"); stored
                }
                PluginOAuthResponse(200, """{"historyId":"999","history":[]}""")
            } else normal(request)
        }
        assertEquals("conflict", host.call("sync", "config" to "personal")["status"].asText())
        assertEquals("1000", host.state()["historyCursor"].asText())
    }

    @Test fun `history gap persists while profile refresh and watch cannot reset it`() = runTest {
        val host = GmailTestHost()
        host.configure()
        val normal = host.respond
        host.respond = { if ("/history?" in it.url) PluginOAuthResponse(404, "{}") else normal(it) }
        assertEquals("resync_required", host.call("sync", "config" to "personal")["status"].asText())
        assertEquals("resync_required", host.call("watch", "config" to "personal")["status"].asText())
        assertEquals("100", host.state()["historyCursor"].asText())
        assertFalse(host.requests.any { it.url.endsWith("/watch") })
    }

    @Test fun `account mismatch and OAuth reconnect stop before history`() = runTest {
        val host = GmailTestHost()
        host.configure()
        host.respond = { PluginOAuthResponse(200, """{"emailAddress":"other@example.com","historyId":"100"}""") }
        assertEquals("account_mismatch", host.call("sync", "config" to "personal")["status"].asText())
        host.respond = { PluginOAuthResponse(authorizationUrl = "https://consent.example/authorize") }
        val reconnect = host.call("sync", "config" to "personal")
        assertEquals("https://consent.example/authorize", reconnect["authorizationUrl"].asText())
        assertEquals("reconnect_required", host.state()["status"].asText())
        assertTrue(host.requests.all { it.url.endsWith("/profile") })
    }

    @Test fun `concurrent sync claims are distinct and concurrent delivery sends once`() = runTest {
        val host = GmailTestHost()
        host.configure()
        val normal = host.respond
        host.respond = {
            if ("/history?" in it.url) PluginOAuthResponse(200, """{"historyId":"100","history":[{"messagesAdded":[{"message":{"id":"m1"}},{"message":{"id":"m2"}}]}]}""") else normal(it)
        }
        val claims = listOf(async { host.claim() }, async { host.claim() }).awaitAll()
        assertEquals(setOf("m1", "m2"), claims.map { it["message"].asText() }.toSet())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        host.send = { entered.complete(Unit); release.await(); true }
        val first = async { host.deliver(claims[0]) }
        entered.await()
        assertEquals("uncertain", host.deliver(claims[0])["status"].asText())
        release.complete(Unit)
        assertEquals("delivered", first.await()["status"].asText())
        assertEquals(1, host.sent.size)
        assertFailsWith<IllegalArgumentException> { host.deliver(claims[0]) }
        assertEquals(1, host.sent.size)
    }

    @Test fun `delivery rejects destination changes and cancellation preserves uncertainty`() = runTest {
        val host = GmailTestHost()
        host.configure()
        val claim = host.claim()
        assertFailsWith<IllegalArgumentException> { host.deliver(claim, "foreign") }
        assertTrue(host.sent.isEmpty())
        host.send = { throw CancellationException("interrupted") }
        assertFailsWith<CancellationException> { host.deliver(claim) }
        assertEquals("uncertain", host.state()["pending"]["m1"]["status"].asText())
        assertEquals("uncertain", host.deliver(claim)["status"].asText())
        assertEquals(1, host.sent.size)
        host.call("resolve_uncertain", "config" to "personal", "message" to "m1", "claim" to claim["claim"].asText(), "outcome" to "delivered")
        assertEquals(0, host.state()["pending"].size())
    }

    @Test fun `expired claims and false send results cannot automatically retry`() = runTest {
        val host = GmailTestHost()
        host.configure()
        val claim = host.claim()
        (host.state()["pending"]["m1"] as ObjectNode).put("expiresAt", 0)
        assertEquals("uncertain", host.deliver(claim)["status"].asText())
        assertTrue(host.sent.isEmpty())
        val another = GmailTestHost()
        another.configure()
        val active = another.claim()
        another.send = { false }
        assertEquals("uncertain", another.deliver(active)["status"].asText())
        assertEquals("uncertain", another.deliver(active)["status"].asText())
        assertEquals(1, another.sent.size)
    }

    @Test fun `cancellation survives a storage cleanup failure and its reservation cannot resend`() = runTest {
        val host = GmailTestHost()
        host.configure()
        val claim = host.claim()
        val cancelled = CancellationException("cancelled during send")
        host.send = { host.updateFailure = IllegalStateException("database unavailable"); throw cancelled }
        assertSame(cancelled, assertFailsWith<CancellationException> { host.deliver(claim) })
        assertEquals(1, cancelled.suppressed.size)
        host.updateFailure = null
        assertEquals("uncertain", host.deliver(claim)["status"].asText())
        assertEquals(1, host.sent.size)
    }

    @Test fun `fixed notification modes prepare their requested amount of content`() = runTest {
        for ((mode, expected) in mapOf("signal" to "В Gmail новое письмо", "subject" to "В Gmail новое письмо. Тема: Subject",
            "summary" to "В Gmail новое письмо. Тема: Subject. snippet")) {
            val host = GmailTestHost()
            host.configure(mode)
            val claim = host.claim()
            val read = host.call("read", "config" to "personal", "message" to "m1", "claim" to claim["claim"].asText())
            assertEquals("prepared", read["status"].asText())
            assertEquals(expected, read["inputs"]["text"].asText())
            assertContains(host.requests.last().url, "format=metadata")
        }
    }

    @Test fun `removed archived and pre-baseline messages are skipped`() = runTest {
        for (mode in listOf("deleted", "archived", "old")) {
            val host = GmailTestHost()
            host.configure("summary")
            val claim = host.claim()
            host.respond = {
                if (mode == "deleted") PluginOAuthResponse(404, "{}") else PluginOAuthResponse(200, mail().apply {
                    if (mode == "old") put("internalDate", "1") else putArray("labelIds")
                }.toString())
            }
            assertEquals("skipped", host.call("read", "config" to "personal", "message" to "m1", "claim" to claim["claim"].asText())["status"].asText())
            assertTrue(host.sent.isEmpty())
            assertEquals(0, host.state()["pending"].size())
        }
    }

    @Test fun `legacy migration preserves state and fences imported live claims`() = runTest {
        val seed = GmailTestHost()
        seed.configure()
        seed.claim()
        val entry = seed.records.getValue("connection:personal")
        val host = GmailTestHost()
        host.texts["/old/connection.json"] = entry["connection"].toString()
        host.texts["/old/state.json"] = entry["state"].toString()
        assertEquals("imported", host.call("import_connection", "config" to "personal", "source" to "/old/connection.json")["status"].asText())
        assertEquals("101", host.state()["historyCursor"].asText())
        assertEquals("uncertain", host.state()["pending"]["m1"]["status"].asText())
        assertFailsWith<IllegalArgumentException> { host.call("import_connection", "config" to "personal", "source" to "/old/connection.json") }
        assertEquals(2, host.texts.size)
    }

    @Test fun `search encodes query and returns no arbitrary API capability`() = runTest {
        val host = GmailTestHost()
        host.respond = { PluginOAuthResponse(200, """{"messages":[{"id":"m1"}]}""") }
        host.call("search", "query" to "from:a@example.com & has:attachment", "maxResults" to 10)
        assertContains(host.requests.single().url, "from%3Aa%40example.com+%26+has%3Aattachment")
        assertTrue(host.requests.single().url.startsWith("https://www.googleapis.com/gmail/v1/users/me/messages?"))
        assertFailsWith<IllegalArgumentException> { host.call("get_message", "message" to "../profile") }
    }

    @Test fun `backlog leaves cursor untouched and deduplication survives page restarts`() = runTest {
        val host = GmailTestHost()
        host.configure()
        host.claim()
        val pending = host.state()["pending"] as ObjectNode
        repeat(499) { pending.putObject("queued$it").put("status", "pending") }
        val normal = host.respond
        host.respond = { request ->
            if ("/history?" in request.url) PluginOAuthResponse(200, """{"historyId":"999","history":[{"messagesAdded":[{"message":{"id":"overflow"}}]}]}""") else normal(request)
        }
        val backlog = host.call("sync", "config" to "personal")
        assertTrue(backlog["moreHistory"].asBoolean())
        assertEquals("101", host.state()["historyCursor"].asText())
        assertFalse((host.state()["pending"] as ObjectNode).has("overflow"))
        val state = newState("owner@example.com")
        repeat(2100) { (state["done"] as com.fasterxml.jackson.databind.node.ArrayNode).add("old$it") }
        finish(state, "latest")
        assertEquals(2101, state["done"].size())
        val revision = host.state()["revision"].asLong()
        assertEquals("conflict", host.call("restart_pages", "config" to "personal", "revision" to revision - 1)["status"].asText())
        assertEquals("ready", host.call("restart_pages", "config" to "personal", "revision" to revision)["status"].asText())
    }
}
