package com.xibeoflower.yttomidi.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * A single extracted note event, mirroring the fields produced by the
 * original Python `extract_midi.py` script (midi pitch, start/duration in
 * seconds, and which hand it belongs to).
 */
data class NoteEvent(
    val midi: Int,
    val startSec: Double,
    val durationSec: Double,
    val hand: Hand
)

enum class Hand { LEFT, RIGHT }

/**
 * Minimal Standard MIDI File (format 1) writer.
 * Writes two tracks: Left Hand (channel 0) and Right Hand (channel 1),
 * matching the two-track layout produced by midiutil in the original script.
 */
class MidiWriter(private val ticksPerQuarter: Int = 480, private val bpm: Int = 120) {

    private val ticksPerSecond: Double = ticksPerQuarter * (bpm / 60.0)

    private fun secondsToTicks(sec: Double): Long =
        Math.round(sec * ticksPerSecond)

    fun write(notes: List<NoteEvent>, outFile: File) {
        outFile.outputStream().use { out ->
            writeHeader(out, numTracks = 2)
            writeTrack(out, trackName = "Left Hand", channel = 0, tempoMeta = true,
                notes = notes.filter { it.hand == Hand.LEFT })
            writeTrack(out, trackName = "Right Hand", channel = 1, tempoMeta = false,
                notes = notes.filter { it.hand == Hand.RIGHT })
        }
    }

    private fun writeHeader(out: OutputStream, numTracks: Int) {
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        writeUInt32(out, 6)
        writeUInt16(out, 1) // format 1
        writeUInt16(out, numTracks)
        writeUInt16(out, ticksPerQuarter)
    }

    private fun writeTrack(
        out: OutputStream,
        trackName: String,
        channel: Int,
        tempoMeta: Boolean,
        notes: List<NoteEvent>
    ) {
        val body = ByteArrayOutputStream()

        // Track name meta event at tick 0
        writeVarLen(body, 0)
        val nameBytes = trackName.toByteArray(Charsets.US_ASCII)
        body.write(0xFF); body.write(0x03); writeVarLen(body, nameBytes.size.toLong()); body.write(nameBytes)

        if (tempoMeta) {
            val microsPerQuarter = (60_000_000L / bpm)
            writeVarLen(body, 0)
            body.write(0xFF); body.write(0x51); body.write(0x03)
            body.write(((microsPerQuarter shr 16) and 0xFF).toInt())
            body.write(((microsPerQuarter shr 8) and 0xFF).toInt())
            body.write((microsPerQuarter and 0xFF).toInt())
        }

        data class RawEvent(val tick: Long, val order: Int, val status: Int, val data1: Int, val data2: Int)

        val events = ArrayList<RawEvent>()
        for (n in notes) {
            val onTick = secondsToTicks(n.startSec)
            val offTick = secondsToTicks(n.startSec + n.durationSec).coerceAtLeast(onTick + 1)
            events.add(RawEvent(onTick, 0, 0x90 or channel, n.midi, 100))
            events.add(RawEvent(offTick, 1, 0x80 or channel, n.midi, 0))
        }
        // Stable sort by tick, note-offs before note-ons when ticks tie
        events.sortWith(compareBy({ it.tick }, { it.order }))

        var prevTick = 0L
        for (e in events) {
            val delta = (e.tick - prevTick).coerceAtLeast(0)
            writeVarLen(body, delta)
            body.write(e.status)
            body.write(e.data1 and 0x7F)
            body.write(e.data2 and 0x7F)
            prevTick = e.tick
        }

        // End of track
        writeVarLen(body, 0)
        body.write(0xFF); body.write(0x2F); body.write(0x00)

        out.write("MTrk".toByteArray(Charsets.US_ASCII))
        val bytes = body.toByteArray()
        writeUInt32(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeUInt32(out: OutputStream, value: Long) {
        out.write(((value shr 24) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    private fun writeUInt16(out: OutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeVarLen(out: OutputStream, value: Long) {
        var buffer = value and 0x7F
        var v = value shr 7
        val stack = ArrayList<Long>()
        stack.add(buffer)
        while (v > 0) {
            buffer = (v and 0x7F) or 0x80
            stack.add(buffer)
            v = v shr 7
        }
        for (i in stack.indices.reversed()) {
            out.write(stack[i].toInt())
        }
    }
}
