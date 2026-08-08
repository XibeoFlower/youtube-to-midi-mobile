package com.xibeoflower.yttomidi.core

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import kotlin.math.roundToInt

data class NotePosition(val x: Int, val isBlack: Boolean)

data class ExtractionSettings(
    val keyY: Int = 500,
    val skipSeconds: Double = 5.0,
    val minDurationSec: Double = 0.03,
    val analysisFps: Double = 20.0, // how many frames per second of video we sample (speed/accuracy trade-off)
    val tempoBpm: Int = 120
)

data class ExtractionResult(
    val notes: List<NoteEvent>,
    val videoWidthAtCalibration: Int = REF_WIDTH,
    val videoHeightAtCalibration: Int = REF_HEIGHT
)

/**
 * Kotlin port of the Python `SynthesiaExtractor` class: samples a small pixel
 * region at each of the 88 piano-key x-positions along a fixed y-line, and
 * classifies it as lit-green (left hand), lit-blue (right hand), or unlit,
 * using the same HSV thresholds as the original OpenCV implementation.
 */
class SynthesiaExtractor(
    private val retriever: MediaMetadataRetriever,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val durationSec: Double,
    private val settings: ExtractionSettings
) {
    companion object {
        // Calibration reference resolution used by the original script (1276x720).
        const val REF_WIDTH = 1276
        const val REF_HEIGHT = 720

        private val DEFAULT_C_LEFT_EDGE = mapOf(
            24 to 51, 36 to 224, 48 to 396, 60 to 567, 72 to 742, 84 to 914, 96 to 1086
        )

        private const val SAMPLE_SIZE = 7
        private const val LIT_THRESHOLD = 0.3
        private const val RELEASE_DEBOUNCE_FRAMES = 2

        // OpenCV hue (0-179) thresholds converted to Android's 0-360 degree hue.
        private const val GREEN_HUE_MIN = 70f   // cv 35 * 2
        private const val GREEN_HUE_MAX = 170f  // cv 85 * 2
        private const val BLUE_HUE_MIN = 170f   // cv 85 * 2
        private const val BLUE_HUE_MAX = 270f   // cv 135 * 2
        private const val SV_MIN = 50f / 255f
    }

    // Scale factor so calibration (done at 1276x720) still lines up on other resolutions.
    private val scaleX = videoWidth.toDouble() / REF_WIDTH
    private val scaleY = videoHeight.toDouble() / REF_HEIGHT

    private val whiteKeyWidth: Double
    private val notePositions = LinkedHashMap<Int, NotePosition>()

    init {
        val cLeftEdge = DEFAULT_C_LEFT_EDGE
        val cNotes = cLeftEdge.keys.sorted()
        val octaveWidths = (0 until cNotes.size - 1).map {
            (cLeftEdge[cNotes[it + 1]]!! - cLeftEdge[cNotes[it]]!!).toDouble()
        }
        val octaveWidth = octaveWidths.average()
        whiteKeyWidth = octaveWidth / 7.0
        buildNotePositions(cLeftEdge)
    }

    private fun buildNotePositions(cLeftEdge: Map<Int, Int>) {
        val whiteSemitones = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        for ((cMidi, cLeft) in cLeftEdge) {
            for (i in whiteSemitones.indices) {
                val midi = cMidi + whiteSemitones[i]
                val x = cLeft + (i + 0.5) * whiteKeyWidth
                notePositions[midi] = NotePosition(scaledX(x), isBlack = false)
            }
        }
        val blackInfo = listOf(
            Triple(1, 0, 1), Triple(3, 1, 2), Triple(6, 3, 4), Triple(8, 4, 5), Triple(10, 5, 6)
        )
        for ((cMidi, cLeft) in cLeftEdge) {
            for ((semitone, leftWhite, _) in blackInfo) {
                val midi = cMidi + semitone
                val x = cLeft + (leftWhite + 1) * whiteKeyWidth
                notePositions[midi] = NotePosition(scaledX(x), isBlack = true)
            }
        }
        val c7Left = cLeftEdge[96]!!
        for (i in whiteSemitones.indices) {
            val midi = 96 + whiteSemitones[i]
            if (midi <= 108) {
                val x = c7Left + (i + 0.5) * whiteKeyWidth
                notePositions[midi] = NotePosition(scaledX(x), isBlack = false)
            }
        }
    }

    private fun scaledX(x: Double): Int = (x * scaleX).roundToInt()
    private val scaledKeyY: Int get() = (settings.keyY * scaleY).roundToInt()

    private data class NoteState(
        var active: Boolean = false,
        var startTime: Double = 0.0,
        var hand: Hand? = null,
        var unlitCount: Int = 999
    )

    /**
     * Runs the frame-sampling loop over the video and returns detected notes.
     * [onProgress] receives a 0f..1f fraction; return false from it to cancel early.
     */
    fun extract(onProgress: (Float) -> Boolean): List<NoteEvent> {
        val stepSec = 1.0 / settings.analysisFps
        val states = HashMap<Int, NoteState>()
        for (midi in notePositions.keys) states[midi] = NoteState()

        val allNotes = ArrayList<NoteEvent>()
        var t = settings.skipSeconds
        val totalSteps = ((durationSec - settings.skipSeconds) / stepSec).toInt().coerceAtLeast(1)
        var step = 0

        while (t < durationSec) {
            val bitmap = retriever.getFrameAtTime(
                (t * 1_000_000).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST
            )
            if (bitmap != null) {
                for ((midi, pos) in notePositions) {
                    val (isLit, hand) = isKeyLit(bitmap, pos)
                    val state = states.getValue(midi)
                    if (isLit) {
                        state.unlitCount = 0
                        if (!state.active) {
                            state.active = true
                            state.startTime = t
                            state.hand = hand
                        }
                    } else {
                        state.unlitCount += 1
                        if (state.active && state.unlitCount >= RELEASE_DEBOUNCE_FRAMES) {
                            val endTime = t - (RELEASE_DEBOUNCE_FRAMES - 1) * stepSec
                            val dur = endTime - state.startTime
                            if (dur >= settings.minDurationSec) {
                                allNotes.add(NoteEvent(midi, state.startTime, dur, state.hand!!))
                            }
                            state.active = false
                        }
                    }
                }
                bitmap.recycle()
            }
            step += 1
            val frac = (step.toFloat() / totalSteps).coerceIn(0f, 1f)
            if (!onProgress(frac)) break
            t += stepSec
        }

        // Close any notes still active at the end of the video.
        for ((midi, state) in states) {
            if (state.active) {
                val dur = durationSec - state.startTime
                if (dur >= settings.minDurationSec) {
                    allNotes.add(NoteEvent(midi, state.startTime, dur, state.hand!!))
                }
            }
        }
        return allNotes
    }

    private val hsv = FloatArray(3)

    private fun isKeyLit(frame: Bitmap, pos: NotePosition): Pair<Boolean, Hand?> {
        val cy = if (pos.isBlack) scaledKeyY + (15 * scaleY).roundToInt() else scaledKeyY
        val cx = if (pos.isBlack) pos.x else pos.x - (3 * scaleX).roundToInt()

        val halfW = SAMPLE_SIZE / 2
        val halfH = SAMPLE_SIZE / 2
        val x1 = (cx - halfW).coerceAtLeast(0)
        val x2 = (cx + halfW + 1).coerceAtMost(frame.width)
        val y1 = (cy - halfH).coerceAtLeast(0)
        val y2 = (cy + halfH + 1).coerceAtMost(frame.height)
        if (x2 <= x1 || y2 <= y1) return false to null

        var greenCount = 0
        var blueCount = 0
        var total = 0
        for (py in y1 until y2) {
            for (px in x1 until x2) {
                val pixel = frame.getPixel(px, py)
                Color.RGBToHSV(Color.red(pixel), Color.green(pixel), Color.blue(pixel), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                total += 1
                if (s > SV_MIN && v > SV_MIN) {
                    if (h in GREEN_HUE_MIN..GREEN_HUE_MAX) greenCount += 1
                    else if (h in BLUE_HUE_MIN..BLUE_HUE_MAX) blueCount += 1
                }
            }
        }
        if (total == 0) return false to null
        val greenRatio = greenCount.toDouble() / total
        val blueRatio = blueCount.toDouble() / total
        if (greenRatio >= LIT_THRESHOLD) return true to Hand.LEFT
        if (blueRatio >= LIT_THRESHOLD) return true to Hand.RIGHT
        return false to null
    }
}
