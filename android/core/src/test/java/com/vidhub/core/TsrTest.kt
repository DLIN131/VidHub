package com.vidhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

class TsrTest {
    private fun page(data: String) = "<script>\$_TSR.router=(\$R=>\$R[0]={matches:\$R[1]=[\$R[2]={i:\"__root__\",l:null},\$R[3]={i:\"x\",l:$data}]})(\$R[\"tsr\"])</script>"

    @Test
    fun parsesValuesAndReferences() {
        val d = Tsr.pageData(page("\$R[4]={ads:\$R[5]={a:1},list:\$R[6]=[\$R[7]={id:\"a\",n:1.5,ok:!0,no:!1,x:null,tags:\$R[8]=[\"中文\"]},\$R[7]],s:\"q\\\"\\u4e2d\\x41\\n\",e:-2e3,\"/k/\":1}"))!!
        assertNull(d["ads"])
        @Suppress("UNCHECKED_CAST")
        val list = d["list"] as List<Map<String, Any?>>
        assertEquals(2, list.size)
        assertEquals("a", list[1]["id"])
        assertEquals(1.5, list[0]["n"])
        assertEquals(true, list[0]["ok"])
        assertEquals(false, list[0]["no"])
        assertEquals(listOf("中文"), list[0]["tags"])
        assertEquals("q\"中A\n", d["s"])
        assertEquals(-2000.0, d["e"])
        assertEquals(1.0, d["/k/"])
    }

    @Test
    fun noDataReturnsNull() {
        assertNull(Tsr.pageData("<html></html>"))
    }

    @Test
    fun unwrapsPngPayload() {
        val payload = "#EXTM3U\n#EXTINF:10,\nhttps://a/b/C-000.png\n".toByteArray()
        val def = Deflater()
        def.setInput(payload)
        def.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        val chunk = byteArrayOf(1) + z.toByteArray()
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            // 一個不相干的區塊，再來才是放內容的區塊
            write(byteArrayOf(0, 0, 0, 2) + "IHDR".toByteArray() + byteArrayOf(9, 9) + byteArrayOf(0, 0, 0, 0))
            write(byteArrayOf(0, 0, 0, chunk.size.toByte()) + byteArrayOf(0x72, 0x6f, 0x55, 0x64) + chunk + byteArrayOf(0, 0, 0, 0))
        }.toByteArray()
        assertEquals(String(payload), String(Stream.unwrap(png)))
        // 不是 PNG 就原樣回傳
        assertEquals("abc", String(Stream.unwrap("abc".toByteArray())))
    }

    /** 本機有存網站頁面時（VIDHUB_PAGES=資料夾）順便用真的頁面測 */
    @Test
    fun realPagesIfAvailable() {
        val dir = System.getenv("VIDHUB_PAGES")?.let(::File) ?: return
        for (f in dir.listFiles { x -> x.name.endsWith(".html") }.orEmpty()) {
            val d = Tsr.pageData(f.readText())
            assertTrue("${f.name} 讀不到資料", d != null && d.isNotEmpty())
            println("${f.name}: ${d!!.keys}")
        }
    }
}
