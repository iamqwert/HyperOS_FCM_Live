/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ----
 *
 * Vendored subset of Google material-color-utilities (Apache-2.0):
 * https://github.com/material-foundation/material-color-utilities
 */
package io.github.howard20181.hyperos.fcmlive.mcu

/** A palette of tones with constant HCT hue and chroma. */
class TonalPalette private constructor(
    val hue: Double,
    val chroma: Double
) {

    private val cache: MutableMap<Int, Int> = HashMap()

    /** ARGB color of this palette at the given HCT tone. */
    fun tone(tone: Double): Int {
        val key = Math.round(tone).toInt()
        var color = cache[key]
        if (color == null) {
            color = if (key == 99 && Hct.isYellow(hue)) {
                averageArgb(tone(98.0), tone(100.0))
            } else {
                Hct.from(hue, chroma, key.toDouble()).toInt()
            }
            cache[key] = color
        }
        return color
    }

    /** This palette at the given tone, as HCT. */
    fun getHct(tone: Double): Hct {
        if (tone == 99.0 && Hct.isYellow(hue)) {
            return Hct.fromInt(tone(99.0))
        }
        return Hct.from(hue, chroma, tone)
    }

    companion object {
        @JvmStatic
        fun fromHct(hct: Hct): TonalPalette = TonalPalette(hct.hue, hct.chroma)

        @JvmStatic
        fun fromHueAndChroma(hue: Double, chroma: Double): TonalPalette = TonalPalette(hue, chroma)

        private fun averageArgb(argb1: Int, argb2: Int): Int {
            val red1 = (argb1 ushr 16) and 0xff
            val green1 = (argb1 ushr 8) and 0xff
            val blue1 = argb1 and 0xff
            val red2 = (argb2 ushr 16) and 0xff
            val green2 = (argb2 ushr 8) and 0xff
            val blue2 = argb2 and 0xff
            val red = Math.round((red1 + red2) / 2f)
            val green = Math.round((green1 + green2) / 2f)
            val blue = Math.round((blue1 + blue2) / 2f)
            return (255 shl 24 or (red and 255) shl 16 or (green and 255) shl 8 or (blue and 255))
        }
    }
}

/** Contrast math used by the 2025 color spec. */
internal object Contrast {

    fun ratioOfYs(y1: Double, y2: Double): Double {
        val lighter = Math.max(y1, y2)
        val darker = if (lighter == y2) y1 else y2
        return (lighter + 5.0) / (darker + 5.0)
    }

    fun ratioOfTones(t1: Double, t2: Double): Double {
        return ratioOfYs(ColorUtils.yFromLstar(t1), ColorUtils.yFromLstar(t2))
    }

    /** Tone >= input tone that reaches ratio; -1 if impossible. */
    fun lighter(tone: Double, ratio: Double): Double {
        if (tone < 0.0 || tone > 100.0) {
            return -1.0
        }
        val darkY = ColorUtils.yFromLstar(tone)
        val lightY = ratio * (darkY + 5.0) - 5.0
        if (lightY < 0.0 || lightY > 100.0) {
            return -1.0
        }
        val realContrast = ratioOfYs(lightY, darkY)
        val delta = Math.abs(realContrast - ratio)
        if (realContrast < ratio && delta > 0.04) {
            return -1.0
        }
        val returnValue = ColorUtils.lstarFromY(lightY) + 0.4
        if (returnValue < 0 || returnValue > 100) {
            return -1.0
        }
        return returnValue
    }

    fun lighterUnsafe(tone: Double, ratio: Double): Double {
        val lighterSafe = lighter(tone, ratio)
        return if (lighterSafe < 0.0) 100.0 else lighterSafe
    }

    /** Tone <= input tone that reaches ratio; -1 if impossible. */
    fun darker(tone: Double, ratio: Double): Double {
        if (tone < 0.0 || tone > 100.0) {
            return -1.0
        }
        val lightY = ColorUtils.yFromLstar(tone)
        val darkY = ((lightY + 5.0) / ratio) - 5.0
        if (darkY < 0.0 || darkY > 100.0) {
            return -1.0
        }
        val realContrast = ratioOfYs(lightY, darkY)
        val delta = Math.abs(realContrast - ratio)
        if (realContrast < ratio && delta > 0.04) {
            return -1.0
        }
        val returnValue = ColorUtils.lstarFromY(darkY) - 0.4
        if (returnValue < 0 || returnValue > 100) {
            return -1.0
        }
        return returnValue
    }

    fun darkerUnsafe(tone: Double, ratio: Double): Double {
        val darkerSafe = darker(tone, ratio)
        return Math.max(0.0, darkerSafe)
    }
}

/** Design utilities using color temperature theory (used by Content / Fidelity). */
internal class TemperatureCache(private val input: Hct) {

    private var precomputedComplement: Hct? = null
    private var precomputedHctsByTemp: List<Hct>? = null
    private var precomputedHctsByHue: List<Hct>? = null
    private var precomputedTempsByHct: Map<Hct, Double>? = null

    val complement: Hct
        get() {
            precomputedComplement?.let { return it }
            val coldestHue = coldest.hue
            val coldestTemp = tempsByHct[coldest]!!
            val warmestHue = warmest.hue
            val warmestTemp = tempsByHct[warmest]!!
            val range = warmestTemp - coldestTemp
            val startHueIsColdestToWarmest = isBetween(input.hue, coldestHue, warmestHue)
            val startHue = if (startHueIsColdestToWarmest) warmestHue else coldestHue
            val endHue = if (startHueIsColdestToWarmest) coldestHue else warmestHue
            val directionOfRotation = 1.0
            var smallestError = 1000.0
            var answer = hctsByHue[Math.round(input.hue).toInt()]!!

            val complementRelativeTemp = 1.0 - getRelativeTemperature(input)
            var hueAddend = 0.0
            while (hueAddend <= 360.0) {
                val hue = MathUtils.sanitizeDegreesDouble(startHue + directionOfRotation * hueAddend)
                if (isBetween(hue, startHue, endHue)) {
                    val possibleAnswer = hctsByHue[Math.round(hue).toInt()]!!
                    val relativeTemp = (tempsByHct[possibleAnswer]!! - coldestTemp) / range
                    val error = Math.abs(complementRelativeTemp - relativeTemp)
                    if (error < smallestError) {
                        smallestError = error
                        answer = possibleAnswer
                    }
                }
                hueAddend += 1.0
            }
            precomputedComplement = answer
            return answer
        }

    fun getAnalogousColors(count: Int, divisions: Int): List<Hct> {
        val startHue = Math.round(input.hue).toInt()
        val startHct = hctsByHue[startHue]!!
        var lastTemp = getRelativeTemperature(startHct)

        val allColors = ArrayList<Hct>()
        allColors.add(startHct)

        var absoluteTotalTempDelta = 0.0
        for (i in 0 until 360) {
            val hue = MathUtils.sanitizeDegreesInt(startHue + i)
            val hct = hctsByHue[hue]!!
            val temp = getRelativeTemperature(hct)
            val tempDelta = Math.abs(temp - lastTemp)
            lastTemp = temp
            absoluteTotalTempDelta += tempDelta
        }

        var hueAddend = 1
        val tempStep = absoluteTotalTempDelta / divisions.toDouble()
        var totalTempDelta = 0.0
        lastTemp = getRelativeTemperature(startHct)
        while (allColors.size < divisions) {
            val hue = MathUtils.sanitizeDegreesInt(startHue + hueAddend)
            val hct = hctsByHue[hue]!!
            val temp = getRelativeTemperature(hct)
            val tempDelta = Math.abs(temp - lastTemp)
            totalTempDelta += tempDelta

            var desiredTotalTempDeltaForIndex = allColors.size * tempStep
            var indexSatisfied = totalTempDelta >= desiredTotalTempDeltaForIndex
            var indexAddend = 1
            while (indexSatisfied && allColors.size < divisions) {
                allColors.add(hct)
                desiredTotalTempDeltaForIndex = (allColors.size + indexAddend) * tempStep
                indexSatisfied = totalTempDelta >= desiredTotalTempDeltaForIndex
                indexAddend++
            }
            lastTemp = temp
            hueAddend++

            if (hueAddend > 360) {
                while (allColors.size < divisions) {
                    allColors.add(hct)
                }
                break
            }
        }

        val answers = ArrayList<Hct>()
        answers.add(input)

        val ccwCount = Math.floor((count - 1.0) / 2.0).toInt()
        for (i in 1 until ccwCount + 1) {
            var index = 0 - i
            while (index < 0) {
                index = allColors.size + index
            }
            if (index >= allColors.size) {
                index = index % allColors.size
            }
            answers.add(0, allColors[index])
        }

        val cwCount = count - ccwCount - 1
        for (i in 1 until cwCount + 1) {
            var index = i
            while (index < 0) {
                index = allColors.size + index
            }
            if (index >= allColors.size) {
                index = index % allColors.size
            }
            answers.add(allColors[index])
        }

        return answers
    }

    fun getRelativeTemperature(hct: Hct): Double {
        val range = tempsByHct[warmest]!! - tempsByHct[coldest]!!
        val differenceFromColdest = tempsByHct[hct]!! - tempsByHct[coldest]!!
        if (range == 0.0) {
            return 0.5
        }
        return differenceFromColdest / range
    }

    private val coldest: Hct
        get() = hctsByTemp[0]

    private val hctsByHue: List<Hct>
        get() {
            precomputedHctsByHue?.let { return it }
            val hcts = ArrayList<Hct>()
            var hue = 0.0
            while (hue <= 360.0) {
                val colorAtHue = Hct.from(hue, input.chroma, input.tone)
                hcts.add(colorAtHue)
                hue += 1.0
            }
            val result = hcts.toList()
            precomputedHctsByHue = result
            return result
        }

    private val hctsByTemp: List<Hct>
        get() {
            precomputedHctsByTemp?.let { return it }
            val hcts = ArrayList(hctsByHue)
            hcts.add(input)
            hcts.sortWith(compareBy { tempsByHct[it]!! })
            precomputedHctsByTemp = hcts
            return hcts
        }

    private val tempsByHct: Map<Hct, Double>
        get() {
            precomputedTempsByHct?.let { return it }
            val allHcts = ArrayList(hctsByHue)
            allHcts.add(input)
            val temperaturesByHct = HashMap<Hct, Double>()
            for (hct in allHcts) {
                temperaturesByHct[hct] = rawTemperature(hct)
            }
            precomputedTempsByHct = temperaturesByHct
            return temperaturesByHct
        }

    private val warmest: Hct
        get() = hctsByTemp[hctsByTemp.size - 1]

    companion object {
        fun rawTemperature(color: Hct): Double {
            val lab = ColorUtils.labFromArgb(color.toInt())
            val hue = MathUtils.sanitizeDegreesDouble(Math.toDegrees(Math.atan2(lab[2], lab[1])))
            val chroma = Math.hypot(lab[1], lab[2])
            return -0.5 +
                0.02 *
                Math.pow(chroma, 1.07) *
                Math.cos(
                    Math.toRadians(
                        MathUtils.sanitizeDegreesDouble(hue - 50.0)
                    )
                )
        }

        private fun isBetween(angle: Double, a: Double, b: Double): Boolean {
            return if (a < b) {
                a <= angle && angle <= b
            } else {
                a <= angle || angle <= b
            }
        }
    }
}

/** Check and/or fix universally disliked colors (dark yellow-greens). */
internal object DislikeAnalyzer {

    fun isDisliked(hct: Hct): Boolean {
        val huePasses = Math.round(hct.hue) >= 90.0 && Math.round(hct.hue) <= 111.0
        val chromaPasses = Math.round(hct.chroma) > 16.0
        val tonePasses = Math.round(hct.tone) < 65.0
        return huePasses && chromaPasses && tonePasses
    }

    fun fixIfDisliked(hct: Hct): Hct {
        return if (isDisliked(hct)) {
            Hct.from(hct.hue, hct.chroma, 70.0)
        } else {
            hct
        }
    }
}
