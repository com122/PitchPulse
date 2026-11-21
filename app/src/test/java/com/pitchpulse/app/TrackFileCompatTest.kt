package com.pitchpulse.app

import com.pitchpulse.app.data.file.TrackFileStore
import com.pitchpulse.app.data.model.TrackPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Section 5 (f): optional new fields keep old files readable and are omitted when absent. */
class TrackFileCompatTest {

    private val dir = Files.createTempDirectory("pp").toFile()
    private val store = TrackFileStore(dir)

    @Test
    fun `f - old files without optional fields parse with zero skipped lines`() = runTest {
        val f = dir.resolve("old.jsonl")
        f.writeText(
            """
            {"t":1732070400000,"lat":22.543101,"lng":113.942011,"acc":3.8,"spd":2.41,"hr":152,"p":1}
            {"t":1732070401000,"lat":22.543111,"lng":113.942021,"acc":3.9,"spd":2.50,"hr":153,"p":1}
            {"t":1732070402000,"lat":22.543121,"lng":113.942031,"acc":4.0,"spd":2.60,"hr":154,"p":0}
            """.trimIndent(),
        )
        val r = store.parse(f)
        assertEquals(0, r.skippedLines)
        assertEquals(3, r.points.size)
        assertTrue(r.points.all { it.tu == null && it.svs == null })
    }

    @Test
    fun `f - new optional fields round trip and are omitted when null`() = runTest {
        val f = dir.resolve("new.jsonl")
        f.writeText(
            """{"t":1732070400000,"lat":22.5,"lng":113.9,"acc":3.8,"spd":2.4,"hr":150,"p":1,"tu":1732070399200,"svs":["G05","E11"]}""" + "\n" +
                """{"t":1732070401000,"lat":22.5,"lng":113.9,"acc":3.8,"spd":2.4,"hr":150,"p":1,"unknownFutureField":42}""",
        )
        val r = store.parse(f)
        assertEquals(0, r.skippedLines)
        assertEquals(1732070399200L, r.points[0].tu)
        assertEquals(listOf("G05", "E11"), r.points[0].svs)

        val out = dir.resolve("written.jsonl")
        store.write(out, listOf(TrackPoint(1L, 22.5, 113.9), TrackPoint(2L, 22.5, 113.9, tu = 1L)))
        val lines = out.readLines()
        assertFalse("absent optional fields must not be written", lines[0].contains("tu"))
        assertFalse(lines[0].contains("svs"))
        assertTrue(lines[0].contains("\"acc\""))
        assertTrue(lines[1].contains("\"tu\":1"))
        assertEquals(0, store.parse(out).skippedLines)
    }
}
