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
 *
 * Only the pieces needed to generate Material Design 3 dynamic color schemes at
 * runtime are kept: HCT (CAM16 + L*), tonal palettes, contrast math and the
 * 2021 / 2025 color specs. Wallpaper quantization (quantize/score) is not
 * included — the seed color comes from the system (Monet) accent.
 */
package io.github.howard20181.hyperos.fcmlive.mcu;

/** HCT: hue from CAM16, chroma from CAM16, tone from L*a*b*. */
public final class Hct {

    /** Create an HCT color from hue, chroma, and tone. */
    public static Hct from(double hue, double chroma, double tone) {
        return new Hct(HctSolver.solveToInt(hue, chroma, tone));
    }

    /** Create an HCT color from an ARGB color. */
    public static Hct fromInt(int argb) {
        return new Hct(argb);
    }

    private double hue;
    private double chroma;
    private double tone;
    private int argb;

    private Hct(int argb) {
        setInternalState(argb);
    }

    public double getHue() {
        return hue;
    }

    public double getChroma() {
        return chroma;
    }

    public double getTone() {
        return tone;
    }

    public int toInt() {
        return argb;
    }

    private void setInternalState(int argb) {
        this.argb = argb;
        Cam16 cam = Cam16.fromInt(argb);
        hue = cam.getHue();
        chroma = cam.getChroma();
        tone = ColorUtils.lstarFromArgb(argb);
    }

    public static boolean isBlue(double hue) {
        return hue >= 250 && hue < 270;
    }

    public static boolean isYellow(double hue) {
        return hue >= 105 && hue < 125;
    }

    public static boolean isCyan(double hue) {
        return hue >= 170 && hue < 207;
    }
}

/** Utility methods for mathematical operations. */
final class MathUtils {
    private MathUtils() {}

    static int signum(double num) {
        if (num < 0) {
            return -1;
        } else if (num == 0) {
            return 0;
        } else {
            return 1;
        }
    }

    static double lerp(double start, double stop, double amount) {
        return (1.0 - amount) * start + amount * stop;
    }

    static int clampInt(int min, int max, int input) {
        if (input < min) {
            return min;
        } else if (input > max) {
            return max;
        }
        return input;
    }

    static double clampDouble(double min, double max, double input) {
        if (input < min) {
            return min;
        } else if (input > max) {
            return max;
        }
        return input;
    }

    static int sanitizeDegreesInt(int degrees) {
        degrees = degrees % 360;
        if (degrees < 0) {
            degrees = degrees + 360;
        }
        return degrees;
    }

    static double sanitizeDegreesDouble(double degrees) {
        degrees = degrees % 360.0;
        if (degrees < 0) {
            degrees = degrees + 360.0;
        }
        return degrees;
    }

    static double[] matrixMultiply(double[] row, double[][] matrix) {
        double a = row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2];
        double b = row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2];
        double c = row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2];
        return new double[] {a, b, c};
    }
}

/** Color science utilities: conversions between ARGB, XYZ and L*a*b*. */
final class ColorUtils {
    private ColorUtils() {}

    static final double[][] SRGB_TO_XYZ =
            new double[][] {
                new double[] {0.41233895, 0.35762064, 0.18051042},
                new double[] {0.2126, 0.7152, 0.0722},
                new double[] {0.01932141, 0.11916382, 0.95034478},
            };

    static final double[][] XYZ_TO_SRGB =
            new double[][] {
                new double[] {3.2413774792388685, -1.5376652402851851, -0.49885366846268053},
                new double[] {-0.9691452513005321, 1.8758853451067872, 0.04156585616912061},
                new double[] {0.05562093689691305, -0.20395524564742123, 1.0571799111220335},
            };

    static final double[] WHITE_POINT_D65 = new double[] {95.047, 100.0, 108.883};

    static int argbFromRgb(int red, int green, int blue) {
        return (255 << 24) | ((red & 255) << 16) | ((green & 255) << 8) | (blue & 255);
    }

    static int argbFromLinrgb(double[] linrgb) {
        int r = delinearized(linrgb[0]);
        int g = delinearized(linrgb[1]);
        int b = delinearized(linrgb[2]);
        return argbFromRgb(r, g, b);
    }

    static int redFromArgb(int argb) {
        return (argb >> 16) & 255;
    }

    static int greenFromArgb(int argb) {
        return (argb >> 8) & 255;
    }

    static int blueFromArgb(int argb) {
        return argb & 255;
    }

    static int argbFromXyz(double x, double y, double z) {
        double[][] matrix = XYZ_TO_SRGB;
        double linearR = matrix[0][0] * x + matrix[0][1] * y + matrix[0][2] * z;
        double linearG = matrix[1][0] * x + matrix[1][1] * y + matrix[1][2] * z;
        double linearB = matrix[2][0] * x + matrix[2][1] * y + matrix[2][2] * z;
        int r = delinearized(linearR);
        int g = delinearized(linearG);
        int b = delinearized(linearB);
        return argbFromRgb(r, g, b);
    }

    static double[] xyzFromArgb(int argb) {
        double r = linearized(redFromArgb(argb));
        double g = linearized(greenFromArgb(argb));
        double b = linearized(blueFromArgb(argb));
        return MathUtils.matrixMultiply(new double[] {r, g, b}, SRGB_TO_XYZ);
    }

    static double[] labFromArgb(int argb) {
        double linearR = linearized(redFromArgb(argb));
        double linearG = linearized(greenFromArgb(argb));
        double linearB = linearized(blueFromArgb(argb));
        double[][] matrix = SRGB_TO_XYZ;
        double x = matrix[0][0] * linearR + matrix[0][1] * linearG + matrix[0][2] * linearB;
        double y = matrix[1][0] * linearR + matrix[1][1] * linearG + matrix[1][2] * linearB;
        double z = matrix[2][0] * linearR + matrix[2][1] * linearG + matrix[2][2] * linearB;
        double[] whitePoint = WHITE_POINT_D65;
        double xNormalized = x / whitePoint[0];
        double yNormalized = y / whitePoint[1];
        double zNormalized = z / whitePoint[2];
        double fx = labF(xNormalized);
        double fy = labF(yNormalized);
        double fz = labF(zNormalized);
        double l = 116.0 * fy - 16;
        double a = 500.0 * (fx - fy);
        double b = 200.0 * (fy - fz);
        return new double[] {l, a, b};
    }

    static int argbFromLstar(double lstar) {
        double y = yFromLstar(lstar);
        int component = delinearized(y);
        return argbFromRgb(component, component, component);
    }

    static double lstarFromArgb(int argb) {
        double y = xyzFromArgb(argb)[1];
        return 116.0 * labF(y / 100.0) - 16.0;
    }

    static double yFromLstar(double lstar) {
        return 100.0 * labInvf((lstar + 16.0) / 116.0);
    }

    static double lstarFromY(double y) {
        return labF(y / 100.0) * 116.0 - 16.0;
    }

    static double linearized(int rgbComponent) {
        double normalized = rgbComponent / 255.0;
        if (normalized <= 0.040449936) {
            return normalized / 12.92 * 100.0;
        } else {
            return Math.pow((normalized + 0.055) / 1.055, 2.4) * 100.0;
        }
    }

    static int delinearized(double rgbComponent) {
        double normalized = rgbComponent / 100.0;
        double delinearized = 0.0;
        if (normalized <= 0.0031308) {
            delinearized = normalized * 12.92;
        } else {
            delinearized = 1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055;
        }
        return MathUtils.clampInt(0, 255, (int) Math.round(delinearized * 255.0));
    }

    static double[] whitePointD65() {
        return WHITE_POINT_D65;
    }

    static double labF(double t) {
        double e = 216.0 / 24389.0;
        double kappa = 24389.0 / 27.0;
        if (t > e) {
            return Math.pow(t, 1.0 / 3.0);
        } else {
            return (kappa * t + 16) / 116;
        }
    }

    static double labInvf(double ft) {
        double e = 216.0 / 24389.0;
        double kappa = 24389.0 / 27.0;
        double ft3 = ft * ft * ft;
        if (ft3 > e) {
            return ft3;
        } else {
            return (116 * ft - 16) / kappa;
        }
    }
}

/** CAM16, a color appearance model. */
final class Cam16 {
    static final double[][] XYZ_TO_CAM16RGB = {
        {0.401288, 0.650173, -0.051461},
        {-0.250268, 1.204414, 0.045854},
        {-0.002079, 0.048952, 0.953127}
    };

    static final double[][] CAM16RGB_TO_XYZ = {
        {1.8620678, -1.0112547, 0.14918678},
        {0.38752654, 0.62144744, -0.00897398},
        {-0.01584150, -0.03412294, 1.0499644}
    };

    private final double hue;
    private final double chroma;
    private final double j;

    private Cam16(double hue, double chroma, double j) {
        this.hue = hue;
        this.chroma = chroma;
        this.j = j;
    }

    static Cam16 fromInt(int argb) {
        return fromIntInViewingConditions(argb, ViewingConditions.DEFAULT);
    }

    static Cam16 fromIntInViewingConditions(int argb, ViewingConditions viewingConditions) {
        int red = (argb & 0x00ff0000) >> 16;
        int green = (argb & 0x0000ff00) >> 8;
        int blue = (argb & 0x000000ff);
        double redL = ColorUtils.linearized(red);
        double greenL = ColorUtils.linearized(green);
        double blueL = ColorUtils.linearized(blue);
        double x = 0.41233895 * redL + 0.35762064 * greenL + 0.18051042 * blueL;
        double y = 0.2126 * redL + 0.7152 * greenL + 0.0722 * blueL;
        double z = 0.01932141 * redL + 0.11916382 * greenL + 0.95034478 * blueL;
        return fromXyzInViewingConditions(x, y, z, viewingConditions);
    }

    static Cam16 fromXyzInViewingConditions(
            double x, double y, double z, ViewingConditions viewingConditions) {
        double[][] matrix = XYZ_TO_CAM16RGB;
        double rT = (x * matrix[0][0]) + (y * matrix[0][1]) + (z * matrix[0][2]);
        double gT = (x * matrix[1][0]) + (y * matrix[1][1]) + (z * matrix[1][2]);
        double bT = (x * matrix[2][0]) + (y * matrix[2][1]) + (z * matrix[2][2]);

        double rD = viewingConditions.getRgbD()[0] * rT;
        double gD = viewingConditions.getRgbD()[1] * gT;
        double bD = viewingConditions.getRgbD()[2] * bT;

        double rAF = Math.pow(viewingConditions.getFl() * Math.abs(rD) / 100.0, 0.42);
        double gAF = Math.pow(viewingConditions.getFl() * Math.abs(gD) / 100.0, 0.42);
        double bAF = Math.pow(viewingConditions.getFl() * Math.abs(bD) / 100.0, 0.42);
        double rA = Math.signum(rD) * 400.0 * rAF / (rAF + 27.13);
        double gA = Math.signum(gD) * 400.0 * gAF / (gAF + 27.13);
        double bA = Math.signum(bD) * 400.0 * bAF / (bAF + 27.13);

        double a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
        double b = (rA + gA - 2.0 * bA) / 9.0;

        double u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0;
        double p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0;

        double atan2 = Math.atan2(b, a);
        double atanDegrees = Math.toDegrees(atan2);
        double hue = MathUtils.sanitizeDegreesDouble(atanDegrees);

        double ac = p2 * viewingConditions.getNbb();

        double j =
                100.0
                        * Math.pow(
                                ac / viewingConditions.getAw(),
                                viewingConditions.getC() * viewingConditions.getZ());

        double huePrime = (hue < 20.14) ? hue + 360 : hue;
        double eHue = 0.25 * (Math.cos(Math.toRadians(huePrime) + 2.0) + 3.8);
        double p1 = 50000.0 / 13.0 * eHue * viewingConditions.getNc() * viewingConditions.getNcb();
        double t = p1 * Math.hypot(a, b) / (u + 0.305);
        double alpha =
                Math.pow(1.64 - Math.pow(0.29, viewingConditions.getN()), 0.73) * Math.pow(t, 0.9);
        double c = alpha * Math.sqrt(j / 100.0);

        return new Cam16(hue, c, j);
    }

    double getHue() {
        return hue;
    }

    double getChroma() {
        return chroma;
    }

    double getJ() {
        return j;
    }
}

/** Cached intermediate values of the CAM16 conversion for one viewing environment. */
final class ViewingConditions {
    /** sRGB-like viewing conditions. */
    static final ViewingConditions DEFAULT = ViewingConditions.defaultWithBackgroundLstar(50.0);

    private final double aw;
    private final double nbb;
    private final double ncb;
    private final double c;
    private final double nc;
    private final double n;
    private final double[] rgbD;
    private final double fl;
    private final double flRoot;
    private final double z;

    private ViewingConditions(
            double n,
            double aw,
            double nbb,
            double ncb,
            double c,
            double nc,
            double[] rgbD,
            double fl,
            double flRoot,
            double z) {
        this.n = n;
        this.aw = aw;
        this.nbb = nbb;
        this.ncb = ncb;
        this.c = c;
        this.nc = nc;
        this.rgbD = rgbD;
        this.fl = fl;
        this.flRoot = flRoot;
        this.z = z;
    }

    static ViewingConditions defaultWithBackgroundLstar(double lstar) {
        return make(
                ColorUtils.whitePointD65(),
                (200.0 / Math.PI * ColorUtils.yFromLstar(50.0) / 100.f),
                lstar,
                2.0,
                false);
    }

    static ViewingConditions make(
            double[] whitePoint,
            double adaptingLuminance,
            double backgroundLstar,
            double surround,
            boolean discountingIlluminant) {
        backgroundLstar = Math.max(0.1, backgroundLstar);
        double[][] matrix = Cam16.XYZ_TO_CAM16RGB;
        double[] xyz = whitePoint;
        double rW = (xyz[0] * matrix[0][0]) + (xyz[1] * matrix[0][1]) + (xyz[2] * matrix[0][2]);
        double gW = (xyz[0] * matrix[1][0]) + (xyz[1] * matrix[1][1]) + (xyz[2] * matrix[1][2]);
        double bW = (xyz[0] * matrix[2][0]) + (xyz[1] * matrix[2][1]) + (xyz[2] * matrix[2][2]);
        double f = 0.8 + (surround / 10.0);
        double c =
                (f >= 0.9)
                        ? MathUtils.lerp(0.59, 0.69, ((f - 0.9) * 10.0))
                        : MathUtils.lerp(0.525, 0.59, ((f - 0.8) * 10.0));
        double d =
                discountingIlluminant
                        ? 1.0
                        : f * (1.0 - ((1.0 / 3.6) * Math.exp((-adaptingLuminance - 42.0) / 92.0)));
        d = MathUtils.clampDouble(0.0, 1.0, d);
        double nc = f;
        double[] rgbD =
                new double[] {
                    d * (100.0 / rW) + 1.0 - d, d * (100.0 / gW) + 1.0 - d, d * (100.0 / bW) + 1.0 - d
                };
        double k = 1.0 / (5.0 * adaptingLuminance + 1.0);
        double k4 = k * k * k * k;
        double k4F = 1.0 - k4;
        double fl = (k4 * adaptingLuminance) + (0.1 * k4F * k4F * Math.cbrt(5.0 * adaptingLuminance));
        double n = (ColorUtils.yFromLstar(backgroundLstar) / whitePoint[1]);
        double z = 1.48 + Math.sqrt(n);
        double nbb = 0.725 / Math.pow(n, 0.2);
        double ncb = nbb;
        double[] rgbAFactors =
                new double[] {
                    Math.pow(fl * rgbD[0] * rW / 100.0, 0.42),
                    Math.pow(fl * rgbD[1] * gW / 100.0, 0.42),
                    Math.pow(fl * rgbD[2] * bW / 100.0, 0.42)
                };
        double[] rgbA =
                new double[] {
                    (400.0 * rgbAFactors[0]) / (rgbAFactors[0] + 27.13),
                    (400.0 * rgbAFactors[1]) / (rgbAFactors[1] + 27.13),
                    (400.0 * rgbAFactors[2]) / (rgbAFactors[2] + 27.13)
                };
        double aw = ((2.0 * rgbA[0]) + rgbA[1] + (0.05 * rgbA[2])) * nbb;
        return new ViewingConditions(n, aw, nbb, ncb, c, nc, rgbD, fl, Math.pow(fl, 0.25), z);
    }

    double getAw() {
        return aw;
    }

    double getN() {
        return n;
    }

    double getNbb() {
        return nbb;
    }

    double getNcb() {
        return ncb;
    }

    double getC() {
        return c;
    }

    double getNc() {
        return nc;
    }

    double[] getRgbD() {
        return rgbD;
    }

    double getFl() {
        return fl;
    }

    double getFlRoot() {
        return flRoot;
    }

    double getZ() {
        return z;
    }
}

/** Solves the HCT equation: finds an sRGB color for a hue, chroma and L*. */
final class HctSolver {
    private HctSolver() {}

    static final double[][] SCALED_DISCOUNT_FROM_LINRGB =
            new double[][] {
                new double[] {
                    0.001200833568784504, 0.002389694492170889, 0.0002795742885861124,
                },
                new double[] {
                    0.0005891086651375999, 0.0029785502573438758, 0.0003270666104008398,
                },
                new double[] {
                    0.00010146692491640572, 0.0005364214359186694, 0.0032979401770712076,
                },
            };

    static final double[][] LINRGB_FROM_SCALED_DISCOUNT =
            new double[][] {
                new double[] {
                    1373.2198709594231, -1100.4251190754821, -7.278681089101213,
                },
                new double[] {
                    -271.815969077903, 559.6580465940733, -32.46047482791194,
                },
                new double[] {
                    1.9622899599665666, -57.173814538844006, 308.7233197812385,
                },
            };

    static final double[] Y_FROM_LINRGB = new double[] {0.2126, 0.7152, 0.0722};

    static final double[] CRITICAL_PLANES =
            new double[] {
                0.015176349177441876, 0.045529047532325624, 0.07588174588720938, 0.10623444424209313,
                0.13658714259697685, 0.16693984095186062, 0.19729253930674434, 0.2276452376616281,
                0.2579979360165119, 0.28835063437139563, 0.3188300904430532, 0.350925934958123,
                0.3848314933096426, 0.42057480301049466, 0.458183274052838, 0.4976837250274023,
                0.5391024159806381, 0.5824650784040898, 0.6277969426914107, 0.6751227633498623,
                0.7244668422128921, 0.775853049866786, 0.829304845476233, 0.8848452951698498,
                0.942497089126609, 1.0022825574869039, 1.0642236851973577, 1.1283421258858297,
                1.1946592148522128, 1.2631959812511864, 1.3339731595349034, 1.407011200216447,
                1.4823302800086415, 1.5599503113873272, 1.6398909516233677, 1.7221716113234105,
                1.8068114625156377, 1.8938294463134073, 1.9832442801866852, 2.075074464868551,
                2.1693382909216234, 2.2660538449872063, 2.36523901573795, 2.4669114995532007,
                2.5710888059345764, 2.6777882626779785, 2.7870270208169257, 2.898822059350997,
                3.0131901897720907, 3.1301480604002863, 3.2497121605402226, 3.3718988244681087,
                3.4967242352587946, 3.624204428461639, 3.754355295633311, 3.887192587735158,
                4.022731918402185, 4.160988767090289, 4.301978482107941, 4.445716283538092,
                4.592217266055746, 4.741496401646282, 4.893568542229298, 5.048448422192488,
                5.20615066083972, 5.3666897647573375, 5.5300801301023865, 5.696336044816294,
                5.865471690767354, 6.037501145825082, 6.212438385869475, 6.390297286737924,
                6.571091626112461, 6.7548350853498045, 6.941541251256611, 7.131223617812143,
                7.323895587840543, 7.5195704746346665, 7.7182615035334345, 7.919981813454504,
                8.124744458384042, 8.332562408825165, 8.543448553206703, 8.757415699253682,
                8.974476575321063, 9.194643831691977, 9.417930041841839, 9.644347703669503,
                9.873909240696694, 10.106627003236781, 10.342513269534024, 10.58158024687427,
                10.8238400726681, 11.069304815507364, 11.317986476196008, 11.569896988756009,
                11.825048221409341, 12.083451977536606, 12.345119996613247, 12.610063955123938,
                12.878295467455942, 13.149826086772048, 13.42466730586372, 13.702830557985108,
                13.984327217668513, 14.269168601521828, 14.55736596900856, 14.848930523210871,
                15.143873411576273, 15.44220572664832, 15.743938506781891, 16.04908273684337,
                16.35764934889634, 16.66964922287304, 16.985093187232053, 17.30399201960269,
                17.62635644741625, 17.95219714852476, 18.281524751807332, 18.614349837764564,
                18.95068293910138, 19.290534541298456, 19.633915083172692, 19.98083495742689,
                20.331304511189067, 20.685334046541502, 21.042933821039977, 21.404114048223256,
                21.76888489811322, 22.137256497705877, 22.50923893145328, 22.884842241736916,
                23.264076429332462, 23.6469514538663, 24.033477234264016, 24.42366364919083,
                24.817520537484558, 25.21505769858089, 25.61628489293138, 26.021211842414342,
                26.429848230738664, 26.842203703840827, 27.258287870275353, 27.678110301598522,
                28.10168053274597, 28.529008062403893, 28.96010235337422, 29.39497283293396,
                29.83362889318845, 30.276079891419332, 30.722335150426627, 31.172403958865512,
                31.62629557157785, 32.08401920991837, 32.54558406207592, 33.010999283389665,
                33.4802739966603, 33.953417292456834, 34.430438229418264, 34.911345834551085,
                35.39614910352207, 35.88485700094671, 36.37747846067349, 36.87402238606382,
                37.37449765026789, 37.87891309649659, 38.38727753828926, 38.89959975977785,
                39.41588851594697, 39.93615253289054, 40.460400508064545, 40.98864111053629,
                41.520882981230194, 42.05713473317016, 42.597404951718396, 43.141702194811224,
                43.6900349931913, 44.24241185063697, 44.798841244188324, 45.35933162437017,
                45.92389141541209, 46.49252901546552, 47.065252796817916, 47.64207110610409,
                48.22299226451468, 48.808024568002054, 49.3971762874833, 49.9904556690408,
                50.587870934119984, 51.189430279724725, 51.79514187861014, 52.40501387947288,
                53.0190544071392, 53.637271562750364, 54.259673423945976, 54.88626804504493,
                55.517063457223934, 56.15206766869424, 56.79128866487574, 57.43473440856916,
                58.08241284012621, 58.734331877617365, 59.39049941699807, 60.05092333227251,
                60.715611475655585, 61.38457167773311, 62.057811747619894, 62.7353394731159,
                63.417162620860914, 64.10328893648692, 64.79372614476921, 65.48848194977529,
                66.18756403501224, 66.89098006357258, 67.59873767827808, 68.31084450182222,
                69.02730813691093, 69.74813616640164, 70.47333615344107, 71.20291564160104,
                71.93688215501312, 72.67524319850172, 73.41800625771542, 74.16517879925733,
                74.9167682708136, 75.67278210128072, 76.43322770089146, 77.1981124613393,
                77.96744375590167, 78.74122893956174, 79.51947534912904, 80.30219030335869,
                81.08938110306934, 81.88105503125999, 82.67721935322541, 83.4778813166706,
                84.28304815182372, 85.09272707154808, 85.90692527145302, 86.72564993000343,
                87.54890820862819, 88.3767072518277, 89.2090541872801, 90.04595612594655,
                90.88742016217518, 91.73345337380438, 92.58406282226491, 93.43925555268066,
                94.29903859396902, 95.16341895893969, 96.03240364439274, 96.9059996312159,
                97.78421388448044, 98.6670533535366, 99.55452497210776,
            };

    static double sanitizeRadians(double angle) {
        return (angle + Math.PI * 8) % (Math.PI * 2);
    }

    static double trueDelinearized(double rgbComponent) {
        double normalized = rgbComponent / 100.0;
        double delinearized = 0.0;
        if (normalized <= 0.0031308) {
            delinearized = normalized * 12.92;
        } else {
            delinearized = 1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055;
        }
        return delinearized * 255.0;
    }

    static double chromaticAdaptation(double component) {
        double af = Math.pow(Math.abs(component), 0.42);
        return MathUtils.signum(component) * 400.0 * af / (af + 27.13);
    }

    static double hueOf(double[] linrgb) {
        double[] scaledDiscount = MathUtils.matrixMultiply(linrgb, SCALED_DISCOUNT_FROM_LINRGB);
        double rA = chromaticAdaptation(scaledDiscount[0]);
        double gA = chromaticAdaptation(scaledDiscount[1]);
        double bA = chromaticAdaptation(scaledDiscount[2]);
        double a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
        double b = (rA + gA - 2.0 * bA) / 9.0;
        return Math.atan2(b, a);
    }

    static boolean areInCyclicOrder(double a, double b, double c) {
        double deltaAB = sanitizeRadians(b - a);
        double deltaAC = sanitizeRadians(c - a);
        return deltaAB < deltaAC;
    }

    static double intercept(double source, double mid, double target) {
        return (mid - source) / (target - source);
    }

    static double[] lerpPoint(double[] source, double t, double[] target) {
        return new double[] {
            source[0] + (target[0] - source[0]) * t,
            source[1] + (target[1] - source[1]) * t,
            source[2] + (target[2] - source[2]) * t,
        };
    }

    static double[] setCoordinate(double[] source, double coordinate, double[] target, int axis) {
        double t = intercept(source[axis], coordinate, target[axis]);
        return lerpPoint(source, t, target);
    }

    static boolean isBounded(double x) {
        return 0.0 <= x && x <= 100.0;
    }

    static double[] nthVertex(double y, int n) {
        double kR = Y_FROM_LINRGB[0];
        double kG = Y_FROM_LINRGB[1];
        double kB = Y_FROM_LINRGB[2];
        double coordA = n % 4 <= 1 ? 0.0 : 100.0;
        double coordB = n % 2 == 0 ? 0.0 : 100.0;
        if (n < 4) {
            double g = coordA;
            double b = coordB;
            double r = (y - g * kG - b * kB) / kR;
            if (isBounded(r)) {
                return new double[] {r, g, b};
            } else {
                return new double[] {-1.0, -1.0, -1.0};
            }
        } else if (n < 8) {
            double b = coordA;
            double r = coordB;
            double g = (y - r * kR - b * kB) / kG;
            if (isBounded(g)) {
                return new double[] {r, g, b};
            } else {
                return new double[] {-1.0, -1.0, -1.0};
            }
        } else {
            double r = coordA;
            double g = coordB;
            double b = (y - r * kR - g * kG) / kB;
            if (isBounded(b)) {
                return new double[] {r, g, b};
            } else {
                return new double[] {-1.0, -1.0, -1.0};
            }
        }
    }

    static double[][] bisectToSegment(double y, double targetHue) {
        double[] left = new double[] {-1.0, -1.0, -1.0};
        double[] right = left;
        double leftHue = 0.0;
        double rightHue = 0.0;
        boolean initialized = false;
        boolean uncut = true;
        for (int n = 0; n < 12; n++) {
            double[] mid = nthVertex(y, n);
            if (mid[0] < 0) {
                continue;
            }
            double midHue = hueOf(mid);
            if (!initialized) {
                left = mid;
                right = mid;
                leftHue = midHue;
                rightHue = midHue;
                initialized = true;
                continue;
            }
            if (uncut || areInCyclicOrder(leftHue, midHue, rightHue)) {
                uncut = false;
                if (areInCyclicOrder(leftHue, targetHue, midHue)) {
                    right = mid;
                    rightHue = midHue;
                } else {
                    left = mid;
                    leftHue = midHue;
                }
            }
        }
        return new double[][] {left, right};
    }

    static double[] midpoint(double[] a, double[] b) {
        return new double[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2};
    }

    static int criticalPlaneBelow(double x) {
        return (int) Math.floor(x - 0.5);
    }

    static int criticalPlaneAbove(double x) {
        return (int) Math.ceil(x - 0.5);
    }

    static double[] bisectToLimit(double y, double targetHue) {
        double[][] segment = bisectToSegment(y, targetHue);
        double[] left = segment[0];
        double leftHue = hueOf(left);
        double[] right = segment[1];
        for (int axis = 0; axis < 3; axis++) {
            if (left[axis] != right[axis]) {
                int lPlane = -1;
                int rPlane = 255;
                if (left[axis] < right[axis]) {
                    lPlane = criticalPlaneBelow(trueDelinearized(left[axis]));
                    rPlane = criticalPlaneAbove(trueDelinearized(right[axis]));
                } else {
                    lPlane = criticalPlaneAbove(trueDelinearized(left[axis]));
                    rPlane = criticalPlaneBelow(trueDelinearized(right[axis]));
                }
                for (int i = 0; i < 8; i++) {
                    if (Math.abs(rPlane - lPlane) <= 1) {
                        break;
                    } else {
                        int mPlane = (int) Math.floor((lPlane + rPlane) / 2.0);
                        double midPlaneCoordinate = CRITICAL_PLANES[mPlane];
                        double[] mid = setCoordinate(left, midPlaneCoordinate, right, axis);
                        double midHue = hueOf(mid);
                        if (areInCyclicOrder(leftHue, targetHue, midHue)) {
                            right = mid;
                            rPlane = mPlane;
                        } else {
                            left = mid;
                            leftHue = midHue;
                            lPlane = mPlane;
                        }
                    }
                }
            }
        }
        return midpoint(left, right);
    }

    static double inverseChromaticAdaptation(double adapted) {
        double adaptedAbs = Math.abs(adapted);
        double base = Math.max(0, 27.13 * adaptedAbs / (400.0 - adaptedAbs));
        return MathUtils.signum(adapted) * Math.pow(base, 1.0 / 0.42);
    }

    static int findResultByJ(double hueRadians, double chroma, double y) {
        double j = Math.sqrt(y) * 11.0;
        ViewingConditions viewingConditions = ViewingConditions.DEFAULT;
        double tInnerCoeff = 1 / Math.pow(1.64 - Math.pow(0.29, viewingConditions.getN()), 0.73);
        double eHue = 0.25 * (Math.cos(hueRadians + 2.0) + 3.8);
        double p1 = eHue * (50000.0 / 13.0) * viewingConditions.getNc() * viewingConditions.getNcb();
        double hSin = Math.sin(hueRadians);
        double hCos = Math.cos(hueRadians);
        for (int iterationRound = 0; iterationRound < 5; iterationRound++) {
            double jNormalized = j / 100.0;
            double alpha = chroma == 0.0 || j == 0.0 ? 0.0 : chroma / Math.sqrt(jNormalized);
            double t = Math.pow(alpha * tInnerCoeff, 1.0 / 0.9);
            double ac =
                    viewingConditions.getAw()
                            * Math.pow(
                                    jNormalized,
                                    1.0 / viewingConditions.getC() / viewingConditions.getZ());
            double p2 = ac / viewingConditions.getNbb();
            double gamma =
                    23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11 * t * hCos + 108.0 * t * hSin);
            double a = gamma * hCos;
            double b = gamma * hSin;
            double rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
            double gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
            double bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;
            double rCScaled = inverseChromaticAdaptation(rA);
            double gCScaled = inverseChromaticAdaptation(gA);
            double bCScaled = inverseChromaticAdaptation(bA);
            double[] linrgb =
                    MathUtils.matrixMultiply(
                            new double[] {rCScaled, gCScaled, bCScaled}, LINRGB_FROM_SCALED_DISCOUNT);
            if (linrgb[0] < 0 || linrgb[1] < 0 || linrgb[2] < 0) {
                return 0;
            }
            double kR = Y_FROM_LINRGB[0];
            double kG = Y_FROM_LINRGB[1];
            double kB = Y_FROM_LINRGB[2];
            double fnj = kR * linrgb[0] + kG * linrgb[1] + kB * linrgb[2];
            if (fnj <= 0) {
                return 0;
            }
            if (iterationRound == 4 || Math.abs(fnj - y) < 0.002) {
                if (linrgb[0] > 100.01 || linrgb[1] > 100.01 || linrgb[2] > 100.01) {
                    return 0;
                }
                return ColorUtils.argbFromLinrgb(linrgb);
            }
            j = j - (fnj - y) * j / (2 * fnj);
        }
        return 0;
    }

    public static int solveToInt(double hueDegrees, double chroma, double lstar) {
        if (chroma < 0.0001 || lstar < 0.0001 || lstar > 99.9999) {
            return ColorUtils.argbFromLstar(lstar);
        }
        hueDegrees = MathUtils.sanitizeDegreesDouble(hueDegrees);
        double hueRadians = hueDegrees / 180 * Math.PI;
        double y = ColorUtils.yFromLstar(lstar);
        int exactAnswer = findResultByJ(hueRadians, chroma, y);
        if (exactAnswer != 0) {
            return exactAnswer;
        }
        double[] linrgb = bisectToLimit(y, hueRadians);
        return ColorUtils.argbFromLinrgb(linrgb);
    }
}
