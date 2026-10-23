package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.anikodev.candywrapper.core.auth.ClientIdentity
import org.anikodev.candywrapper.core.auth.EnvironmentApi
import org.anikodev.candywrapper.core.auth.PairingInputError
import org.anikodev.candywrapper.core.auth.parsePairingInput
import org.anikodev.candywrapper.core.markdown.Markdown
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.markdown.MdSpan
import org.anikodev.candywrapper.core.model.DirectRoute
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.core.sync.Routes

class ParsingTest {
    @Test
    fun readsEveryPairingForm() {
        val direct = parsePairingInput("http://192.168.1.5:3773/pair#token=ABCD2345EFGH")
        assertEquals("http://192.168.1.5:3773/", direct.httpBase)
        assertEquals("ws://192.168.1.5:3773/", direct.wsBase)
        assertEquals("ABCD2345EFGH", direct.code)
        assertEquals("ABCD2345EFGH", parsePairingInput("http://192.168.1.5:3773/pair?token=ABCD2345EFGH").code)
        val hosted = parsePairingInput("https://app.t3.codes/pair?host=https%3A%2F%2Fmac.tailnet.ts.net&label=Mac#token=ABCD2345EFGH")
        assertEquals("https://mac.tailnet.ts.net/", hosted.httpBase)
        assertEquals("wss://mac.tailnet.ts.net/", hosted.wsBase)
        assertEquals("Mac", hosted.label)
        val manual = parsePairingInput("mac.tailnet.ts.net", "ABCD2345EFGH")
        assertEquals("https://mac.tailnet.ts.net/", manual.httpBase)
        assertFailsWith<PairingInputError> { parsePairingInput("http://192.168.1.5:3773/pair") }
        // The code never shows up in logs.
        assertTrue("ABCD" !in direct.toString())
    }

    @Test
    fun socketUrlCarriesProtocolAndClient() {
        ClientIdentity.appVersion = "1.0"
        val url = EnvironmentApi.socketUrl("wss://prod-abc.example/", "T", "relay").replace("wss://", "https://").toHttpUrl()
        assertEquals("/ws", url.encodedPath)
        assertEquals("2", url.queryParameter("orchestrationProtocol"))
        assertEquals("T", url.queryParameter("wsTicket"))
        assertEquals("relay", url.queryParameter("connectionMethod"))
        assertEquals("Android", url.queryParameter("clientOs"))
        assertTrue(EnvironmentApi.socketUrl("ws://10.0.2.2:3773/", "T", "direct").startsWith("ws://10.0.2.2:3773/ws?"))
    }

    @Test
    fun routesPutThePrimaryFirstAndDropLoopback() {
        val routes = Routes.build("https://tunnel.example/", "wss://tunnel.example/", "tunnel", listOf(
            DirectRoute("tailnet", "http://100.64.0.2:3773"), DirectRoute("lan", "http://192.168.1.5:3773/"),
            DirectRoute("lan", "http://127.0.0.1:3773"), DirectRoute("lan", "http://192.168.1.5:3773")))
        assertEquals(listOf("tunnel", "lan", "tailnet"), routes.map { it.label })
        assertEquals(false, routes[0].verifyFirst)
        assertTrue(routes.drop(1).all { it.verifyFirst })
        assertEquals("ws://192.168.1.5:3773/", routes[1].wsBase)
    }

    @Test
    fun backoffStaysInTheUpperHalf() {
        for (n in 0..12) {
            val ceiling = minOf(300_000L, 1000L shl (n + 1))
            val d = EnvironmentSession.backoff(n)
            assertTrue(d >= ceiling / 2 && d <= ceiling, "n=$n d=$d")
        }
    }

    @Test
    fun markdownBlocks() {
        val doc = Markdown.parse("""
            # Title
            Some **bold** and *italic* and `code` and ~~gone~~.
            Next line stays a line.

            - one
            - [x] done
              continued
            1. first
            2. second

            ```kotlin
            val x = 1 // **not bold**
            ```
            > quoted *text*

            | a | b |
            |---|:-:|
            | 1 | 2 |
            ---
            See [the docs](https://example.com/d) or https://t3.codes.
        """.trimIndent())
        assertEquals(listOf("Heading", "Paragraph", "ListBlock", "ListBlock", "Code", "Quote", "Table", "Rule", "Paragraph"),
            doc.map { it::class.simpleName })
        val para = doc[1] as MdBlock.Paragraph
        assertEquals(MdSpan("bold", MdSpan.BOLD), para.spans[1])
        assertEquals(MdSpan("italic", MdSpan.ITALIC), para.spans[3])
        assertEquals(MdSpan("code", MdSpan.CODE), para.spans[5])
        assertEquals(MdSpan("gone", MdSpan.STRIKE), para.spans[7])
        assertTrue(para.spans.last().text.contains("\nNext line"))
        val bullets = doc[2] as MdBlock.ListBlock
        assertEquals(2, bullets.items.size)
        assertEquals(true, bullets.items[1].checked)
        assertEquals("done\ncontinued", ((bullets.items[1].blocks[0]) as MdBlock.Paragraph).spans.joinToString("") { it.text })
        assertEquals(true, (doc[3] as MdBlock.ListBlock).ordered)
        assertEquals("val x = 1 // **not bold**", (doc[4] as MdBlock.Code).code)
        assertEquals("kotlin", (doc[4] as MdBlock.Code).language)
        val table = doc[6] as MdBlock.Table
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
        val links = (doc[8] as MdBlock.Paragraph).spans.filter { it.link != null }
        assertEquals(listOf("https://example.com/d", "https://t3.codes"), links.map { it.link })
    }

    @Test
    fun markdownLeavesUnclosedMarkersAndSnakeCase() {
        val spans = Markdown.inlines("a * b and snake_case_name and 2*3")
        assertEquals(1, spans.size)
        assertIs<MdSpan>(spans.single())
        assertEquals(MdSpan("x", MdSpan.BOLD or MdSpan.ITALIC), Markdown.inlines("***x***").single())
    }
}
