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
package io.github.howard20181.hyperos.fcmlive.mcu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A palette of tones with constant HCT hue and chroma. */
public final class TonalPalette {

    private final Map<Integer, Integer> cache = new HashMap<>();
    private final double hue;
    private final double chroma;

    private TonalPalette(double hue, double chroma) {
        this.hue = hue;
        this.chroma = chroma;
    }

    public static TonalPalette fromHct(Hct hct) {
        return new TonalPalette(hct.getHue(), hct.getChroma());
    }

    public static TonalPalette fromHueAndChroma(double hue, double chroma) {
        return new TonalPalette(hue, chroma);
    }

    /** ARGB color of this palette at the given HCT tone. */
    public int tone(double tone) {
        int key = (int) Math.round(tone);
        Integer color = cache.get(key);
        if (color == null) {
            if (key == 99 && Hct.isYellow(hue)) {
                color = averageArgb(tone(98), tone(100));
            } else {
                color = Hct.from(hue, chroma, key).toInt();
            }
            cache.put(key, color);
        }
        return color;
    }

    /** This palette at the given tone, as HCT. */
    public Hct getHct(double tone) {
        if (tone == 99.0 && Hct.isYellow(hue)) {
            return Hct.fromInt(tone(99));
        }
        return Hct.from(hue, chroma, tone);
    }

    public double getChroma() {
        return chroma;
    }

    public double getHue() {
        return hue;
    }

    private static int averageArgb(int argb1, int argb2) {
        int red1 = (argb1 >>> 16) & 0xff;
        int green1 = (argb1 >>> 8) & 0xff;
        int blue1 = argb1 & 0xff;
        int red2 = (argb2 >>> 16) & 0xff;
        int green2 = (argb2 >>> 8) & 0xff;
        int blue2 = argb2 & 0xff;
        int red = Math.round((red1 + red2) / 2f);
        int green = Math.round((green1 + green2) / 2f);
        int blue = Math.round((blue1 + blue2) / 2f);
        return (255 << 24 | (red & 255) << 16 | (green & 255) << 8 | (blue & 255));
    }
}

/** Contrast math used by the 2025 color spec. */
final class Contrast {
    private Contrast() {}

    static double ratioOfYs(double y1, double y2) {
        final double lighter = Math.max(y1, y2);
        final double darker = (lighter == y2) ? y1 : y2;
        return (lighter + 5.0) / (darker + 5.0);
    }

    static double ratioOfTones(double t1, double t2) {
        return ratioOfYs(ColorUtils.yFromLstar(t1), ColorUtils.yFromLstar(t2));
    }

    /** Tone >= input tone that reaches ratio; -1 if impossible. */
    static double lighter(double tone, double ratio) {
        if (tone < 0.0 || tone > 100.0) {
            return -1.0;
        }
        final double darkY = ColorUtils.yFromLstar(tone);
        final double lightY = ratio * (darkY + 5.0) - 5.0;
        if (lightY < 0.0 || lightY > 100.0) {
            return -1.0;
        }
        final double realContrast = ratioOfYs(lightY, darkY);
        final double delta = Math.abs(realContrast - ratio);
        if (realContrast < ratio && delta > 0.04) {
            return -1.0;
        }
        final double returnValue = ColorUtils.lstarFromY(lightY) + 0.4;
        if (returnValue < 0 || returnValue > 100) {
            return -1.0;
        }
        return returnValue;
    }

    static double lighterUnsafe(double tone, double ratio) {
        double lighterSafe = lighter(tone, ratio);
        return lighterSafe < 0.0 ? 100.0 : lighterSafe;
    }

    /** Tone <= input tone that reaches ratio; -1 if impossible. */
    static double darker(double tone, double ratio) {
        if (tone < 0.0 || tone > 100.0) {
            return -1.0;
        }
        final double lightY = ColorUtils.yFromLstar(tone);
        final double darkY = ((lightY + 5.0) / ratio) - 5.0;
        if (darkY < 0.0 || darkY > 100.0) {
            return -1.0;
        }
        final double realContrast = ratioOfYs(lightY, darkY);
        final double delta = Math.abs(realContrast - ratio);
        if (realContrast < ratio && delta > 0.04) {
            return -1.0;
        }
        final double returnValue = ColorUtils.lstarFromY(darkY) - 0.4;
        if (returnValue < 0 || returnValue > 100) {
            return -1.0;
        }
        return returnValue;
    }

    static double darkerUnsafe(double tone, double ratio) {
        double darkerSafe = darker(tone, ratio);
        return Math.max(0.0, darkerSafe);
    }
}

/** Design utilities using color temperature theory (used by Content / Fidelity). */
final class TemperatureCache {
    private final Hct input;

    private Hct precomputedComplement;
    private List<Hct> precomputedHctsByTemp;
    private List<Hct> precomputedHctsByHue;
    private Map<Hct, Double> precomputedTempsByHct;

    TemperatureCache(Hct input) {
        this.input = input;
    }

    public Hct getComplement() {
        if (precomputedComplement != null) {
            return precomputedComplement;
        }
        double coldestHue = getColdest().getHue();
        double coldestTemp = getTempsByHct().get(getColdest());
        double warmestHue = getWarmest().getHue();
        double warmestTemp = getTempsByHct().get(getWarmest());
        double range = warmestTemp - coldestTemp;
        boolean startHueIsColdestToWarmest = isBetween(input.getHue(), coldestHue, warmestHue);
        double startHue = startHueIsColdestToWarmest ? warmestHue : coldestHue;
        double endHue = startHueIsColdestToWarmest ? coldestHue : warmestHue;
        double directionOfRotation = 1.;
        double smallestError = 1000.;
        Hct answer = getHctsByHue().get((int) Math.round(input.getHue()));

        double complementRelativeTemp = (1. - getRelativeTemperature(input));
        for (double hueAddend = 0.; hueAddend <= 360.; hueAddend += 1.) {
            double hue = MathUtils.sanitizeDegreesDouble(startHue + directionOfRotation * hueAddend);
            if (!isBetween(hue, startHue, endHue)) {
                continue;
            }
            Hct possibleAnswer = getHctsByHue().get((int) Math.round(hue));
            double relativeTemp = (getTempsByHct().get(possibleAnswer) - coldestTemp) / range;
            double error = Math.abs(complementRelativeTemp - relativeTemp);
            if (error < smallestError) {
                smallestError = error;
                answer = possibleAnswer;
            }
        }
        precomputedComplement = answer;
        return precomputedComplement;
    }

    public List<Hct> getAnalogousColors(int count, int divisions) {
        int startHue = (int) Math.round(input.getHue());
        Hct startHct = getHctsByHue().get(startHue);
        double lastTemp = getRelativeTemperature(startHct);

        List<Hct> allColors = new ArrayList<>();
        allColors.add(startHct);

        double absoluteTotalTempDelta = 0.f;
        for (int i = 0; i < 360; i++) {
            int hue = MathUtils.sanitizeDegreesInt(startHue + i);
            Hct hct = getHctsByHue().get(hue);
            double temp = getRelativeTemperature(hct);
            double tempDelta = Math.abs(temp - lastTemp);
            lastTemp = temp;
            absoluteTotalTempDelta += tempDelta;
        }

        int hueAddend = 1;
        double tempStep = absoluteTotalTempDelta / (double) divisions;
        double totalTempDelta = 0.0;
        lastTemp = getRelativeTemperature(startHct);
        while (allColors.size() < divisions) {
            int hue = MathUtils.sanitizeDegreesInt(startHue + hueAddend);
            Hct hct = getHctsByHue().get(hue);
            double temp = getRelativeTemperature(hct);
            double tempDelta = Math.abs(temp - lastTemp);
            totalTempDelta += tempDelta;

            double desiredTotalTempDeltaForIndex = (allColors.size() * tempStep);
            boolean indexSatisfied = totalTempDelta >= desiredTotalTempDeltaForIndex;
            int indexAddend = 1;
            while (indexSatisfied && allColors.size() < divisions) {
                allColors.add(hct);
                desiredTotalTempDeltaForIndex = ((allColors.size() + indexAddend) * tempStep);
                indexSatisfied = totalTempDelta >= desiredTotalTempDeltaForIndex;
                indexAddend++;
            }
            lastTemp = temp;
            hueAddend++;

            if (hueAddend > 360) {
                while (allColors.size() < divisions) {
                    allColors.add(hct);
                }
                break;
            }
        }

        List<Hct> answers = new ArrayList<>();
        answers.add(input);

        int ccwCount = (int) Math.floor(((double) count - 1.0) / 2.0);
        for (int i = 1; i < (ccwCount + 1); i++) {
            int index = 0 - i;
            while (index < 0) {
                index = allColors.size() + index;
            }
            if (index >= allColors.size()) {
                index = index % allColors.size();
            }
            answers.add(0, allColors.get(index));
        }

        int cwCount = count - ccwCount - 1;
        for (int i = 1; i < (cwCount + 1); i++) {
            int index = i;
            while (index < 0) {
                index = allColors.size() + index;
            }
            if (index >= allColors.size()) {
                index = index % allColors.size();
            }
            answers.add(allColors.get(index));
        }

        return answers;
    }

    public double getRelativeTemperature(Hct hct) {
        double range = getTempsByHct().get(getWarmest()) - getTempsByHct().get(getColdest());
        double differenceFromColdest = getTempsByHct().get(hct) - getTempsByHct().get(getColdest());
        if (range == 0.) {
            return 0.5;
        }
        return differenceFromColdest / range;
    }

    public static double rawTemperature(Hct color) {
        double[] lab = ColorUtils.labFromArgb(color.toInt());
        double hue =
                MathUtils.sanitizeDegreesDouble(Math.toDegrees(Math.atan2(lab[2], lab[1])));
        double chroma = Math.hypot(lab[1], lab[2]);
        return -0.5
                + 0.02
                        * Math.pow(chroma, 1.07)
                        * Math.cos(
                                Math.toRadians(
                                        MathUtils.sanitizeDegreesDouble(hue - 50.)));
    }

    private Hct getColdest() {
        return getHctsByTemp().get(0);
    }

    private List<Hct> getHctsByHue() {
        if (precomputedHctsByHue != null) {
            return precomputedHctsByHue;
        }
        List<Hct> hcts = new ArrayList<>();
        for (double hue = 0.; hue <= 360.; hue += 1.) {
            Hct colorAtHue = Hct.from(hue, input.getChroma(), input.getTone());
            hcts.add(colorAtHue);
        }
        precomputedHctsByHue = Collections.unmodifiableList(hcts);
        return precomputedHctsByHue;
    }

    private List<Hct> getHctsByTemp() {
        if (precomputedHctsByTemp != null) {
            return precomputedHctsByTemp;
        }
        List<Hct> hcts = new ArrayList<>(getHctsByHue());
        hcts.add(input);
        Comparator<Hct> temperaturesComparator =
                Comparator.comparing((Hct arg) -> getTempsByHct().get(arg), Double::compareTo);
        Collections.sort(hcts, temperaturesComparator);
        precomputedHctsByTemp = hcts;
        return precomputedHctsByTemp;
    }

    private Map<Hct, Double> getTempsByHct() {
        if (precomputedTempsByHct != null) {
            return precomputedTempsByHct;
        }
        List<Hct> allHcts = new ArrayList<>(getHctsByHue());
        allHcts.add(input);
        Map<Hct, Double> temperaturesByHct = new HashMap<>();
        for (Hct hct : allHcts) {
            temperaturesByHct.put(hct, rawTemperature(hct));
        }
        precomputedTempsByHct = temperaturesByHct;
        return precomputedTempsByHct;
    }

    private Hct getWarmest() {
        return getHctsByTemp().get(getHctsByTemp().size() - 1);
    }

    private static boolean isBetween(double angle, double a, double b) {
        if (a < b) {
            return a <= angle && angle <= b;
        }
        return a <= angle || angle <= b;
    }
}

/** Check and/or fix universally disliked colors (dark yellow-greens). */
final class DislikeAnalyzer {
    private DislikeAnalyzer() {}

    static boolean isDisliked(Hct hct) {
        final boolean huePasses = Math.round(hct.getHue()) >= 90.0 && Math.round(hct.getHue()) <= 111.0;
        final boolean chromaPasses = Math.round(hct.getChroma()) > 16.0;
        final boolean tonePasses = Math.round(hct.getTone()) < 65.0;
        return huePasses && chromaPasses && tonePasses;
    }

    static Hct fixIfDisliked(Hct hct) {
        if (isDisliked(hct)) {
            return Hct.from(hct.getHue(), hct.getChroma(), 70.0);
        }
        return hct;
    }
}
