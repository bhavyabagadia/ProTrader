package com.pricealert.util;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small helpers for printing prices, percentages, times and (optional) terminal colours. */
public final class Fmt {
    private Fmt() { }

    /** Colours use ANSI escape codes. Main turns this off by default on Windows consoles. */
    public static volatile boolean color = true;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 43125.5 -> "43,125.50"; small coin prices get more decimals so they stay readable. */
    public static String price(double p) {
        if (Double.isNaN(p) || Double.isInfinite(p)) return "n/a";
        double a = Math.abs(p);
        int decimals = a >= 100 ? 2 : a >= 1 ? 4 : a >= 0.01 ? 6 : 8;
        return String.format(Locale.US, "%,." + decimals + "f", p);
    }

    /** A price CHANGE, e.g. "+12.30". Changes are small numbers, so they need fewer decimals than prices do. */
    public static String signedPrice(double d) {
        double a = Math.abs(d);
        int decimals = a >= 0.01 ? 2 : a >= 0.0001 ? 6 : 8;
        return (d < 0 ? "-" : "+") + String.format(Locale.US, "%,." + decimals + "f", a);
    }

    /** 1.5 -> "+1.500%" */
    public static String pct(double p) {
        return String.format(Locale.US, "%+.3f%%", p);
    }

    /** 2.0 -> "2", 0.025 -> "0.025" (no pointless trailing zeros). */
    public static String plain(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    public static String time(Instant t) { return TIME.format(t); }

    public static String stamp(Instant t) { return STAMP.format(t); }

    private static String paint(String code, String s) {
        return color ? "\u001B[" + code + "m" + s + "\u001B[0m" : s;
    }

    public static String green(String s) { return paint("32", s); }

    public static String red(String s) { return paint("31", s); }

    public static String yellow(String s) { return paint("33", s); }

    public static String bold(String s) { return paint("1", s); }
}
