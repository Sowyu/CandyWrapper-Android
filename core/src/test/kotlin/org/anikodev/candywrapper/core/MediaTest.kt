package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.diff.ItemDetail
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.markdown.Markdown
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.media.AssetUrl
import org.anikodev.candywrapper.core.media.AssetUrls
import org.anikodev.candywrapper.core.media.ChatAttachment
import org.anikodev.candywrapper.core.media.ImageRef
import org.anikodev.candywrapper.core.media.Media
import org.anikodev.candywrapper.core.model.TurnRow

/** Images: message attachments, asset resources (contracts assets.ts), Markdown sources and tool output images. */
class MediaTest {
    private fun json(s: String) = WireJson.parseToJsonElement(s) as JsonObject

    @Test
    fun messagesKeepTheirAttachmentsAndToolsTheirViewedImage() {
        val user = TurnRow.fromItem(json("""{"id":"i1","type":"user_message","text":"look","attachments":[
            {"type":"image","id":"att-1","name":"shot.png","mimeType":"image/png","sizeBytes":2048},
            {"type":"file","id":"att-2","name":"photo.JPG","mimeType":"image/jpeg","sizeBytes":10},
            {"type":"file","id":"att-3","name":"notes.pdf","mimeType":"application/pdf","sizeBytes":10}]}"""))!!
        assertEquals(listOf("att-1", "att-2", "att-3"), user.attachments.map { it.id })
        assertEquals(listOf(true, true, false), user.attachments.map { it.isImage })
        val assistant = TurnRow.fromItem(json("""{"id":"i2","type":"assistant_message","text":"done","streaming":false,
            "attachments":[{"type":"image","id":"gen","name":"chart.webp","mimeType":"image/webp","sizeBytes":1}]}"""))!!
        assertEquals("gen", assistant.attachments.single().id)
        val tool = TurnRow.fromItem(json("""{"id":"i3","type":"dynamic_tool","toolName":"view_image","viewedImagePath":"/Users/a/shot.png","input":{}}"""))!!
        assertEquals("/Users/a/shot.png", tool.viewedImagePath)
    }

    @Test
    fun resourcesMatchTheContract() {
        val a = Media.attachment(ChatAttachment("image", "att-1", "shot.png", "image/png", 1))
        assertEquals(json("""{"_tag":"attachment","attachmentId":"att-1","fileName":"shot.png","mimeType":"image/png","disposition":"inline"}"""), a.resource)
        assertEquals("attachment:att-1", a.key)
        assertEquals(json("""{"_tag":"media-file","threadId":"t","path":"/a/b.png"}"""), Media.mediaFile("t", "/a/b.png").resource)
        assertEquals(json("""{"_tag":"tool-output-image","threadId":"t","itemId":"i","index":2}"""), Media.toolOutput("t", "i", 2).resource)
    }

    @Test
    fun markdownSourcesLoadDirectlyOrAsHostFiles() {
        assertEquals(ImageRef.Direct("https://x.dev/a.png", "a", "https://x.dev/a.png"), Media.markdownImage("https://x.dev/a.png", "a", "t"))
        assertEquals("https://cdn/x.png", (Media.markdownImage("//cdn/x.png", "", "t") as ImageRef.Direct).url)
        assertEquals(Media.mediaFile("t", "/Users/a/my shot.png"), Media.markdownImage("file:///Users/a/my%20shot.png", "", "t"))
        assertEquals(Media.mediaFile("t", "docs/img.png"), Media.markdownImage("<docs/img.png?raw=1>", "", "t"))
        assertEquals(Media.mediaFile("t", "C:\\shots\\a.png"), Media.markdownImage("/C:\\shots\\a.png", "", "t"))
        for (blocked in listOf("", "#top", "javascript:alert(1)", "~/a.png", "mailto:a@b")) assertNull(Media.markdownImage(blocked, "", "t"), blocked)
        // Without a thread a host path has nowhere to resolve.
        assertNull(Media.markdownImage("/a.png", "", null))
    }

    @Test
    fun markdownLiftsImagesOutOfTheirParagraph() {
        val blocks = Markdown.parse("Before ![a chart](out/chart.png) after\n\n[link](https://x.dev) only")
        assertEquals(4, blocks.size)
        assertEquals("Before ", (blocks[0] as MdBlock.Paragraph).spans.joinToString("") { it.text })
        assertEquals(MdBlock.Image("out/chart.png", "a chart"), blocks[1])
        assertEquals(" after", (blocks[2] as MdBlock.Paragraph).spans.joinToString("") { it.text })
        assertIs<MdBlock.Paragraph>(blocks[3])
        // Two images in a row stay two blocks.
        assertEquals(2, Markdown.parse("![](a.png)\n![](b.png)").count { it is MdBlock.Image })
    }

    @Test
    fun toolOutputImagesCountReadableRasterBlocksOnly() {
        val output = WireJson.parseToJsonElement("""{"content":[{"type":"text","text":"hi"},
            {"type":"image","data":"AAAA","mimeType":"image/png"},
            {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"AAAA"}},
            {"type":"image","source":{"type":"url","url":"https://x"}},
            {"type":"image","data":"AAAA","mimeType":"image/svg+xml"}]}""")
        assertEquals(2, Media.toolOutputImageCount(output))
        val detail = ItemDetail.from(json("""{"type":"dynamic_tool","input":{"q":1},"output":$output}"""))
        assertEquals(2, detail.images)
        // The base64 never reaches the text box.
        assertEquals(false, detail.text!!.contains("AAAA"))
        assertEquals(0, ItemDetail.from(json("""{"type":"dynamic_tool","input":{},"output":$output,"outputOmitted":true}""")).images)
    }

    @Test
    fun signedUrlsAreReusedUntilFiveMinutesBeforeExpiry() = runTest {
        var now = 1_000_000L
        var minted = 0
        val urls = AssetUrls({ now }) { minted++; AssetUrl("/api/assets/x/$minted", now + 60 * 60_000, 800, 600) }
        val ref = Media.mediaFile("t", "/a.png")
        assertEquals("/api/assets/x/1", urls.get(ref).relativeUrl)
        now += 50 * 60_000
        assertEquals("/api/assets/x/1", urls.get(ref).relativeUrl)
        assertEquals(800 to 600, urls.size(ref.key))
        now += 6 * 60_000
        assertEquals("/api/assets/x/2", urls.get(ref).relativeUrl)
        assertEquals(AssetUrl("/r", 5, 3, 2), AssetUrl.from(json("""{"relativeUrl":"/r","expiresAt":5,"imageDimensions":{"width":3,"height":2}}""")))
    }

    @Test
    fun boxesKeepTheRatioAndNeverEnlarge() {
        assertEquals(300f to 150f, Media.fit(1200, 600, 300f, 480f))
        assertEquals(100f to 50f, Media.fit(100, 50, 300f, 480f))
        assertEquals(240f to 480f, Media.fit(1000, 2000, 300f, 480f))
        assertEquals(320f to 180f, Media.fit(0, 0, 320f, 480f))
    }
}
