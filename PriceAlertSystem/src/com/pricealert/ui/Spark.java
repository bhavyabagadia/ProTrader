package com.pricealert.ui;

import java.util.List;

/** Draws a tiny text chart ("sparkline") of recent prices, e.g. ▁▂▃▅▇▆▄. */
public final class Spark {
    private Spark() { }

    private static final String BLOCKS = "▁▂▃▄▅▆▇█";
    private static final String ASCII = ".:-=+*#@";

    public static volatile boolean unicode = true;

    public static String draw(List<Double> values, int width) {
        if (values.isEmpty()) return "";
        List<Double> v = values.size() > width ? values.subList(values.size() - width, values.size()) : values;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double d : v) { min = Math.min(min, d); max = Math.max(max, d); }
        String set = unicode ? BLOCKS : ASCII;
        StringBuilder sb = new StringBuilder();
        for (double d : v) {
            int idx = max == min ? set.length() / 2 : (int) Math.round((d - min) / (max - min) * (set.length() - 1));
            sb.append(set.charAt(idx));
        }
        return sb.toString();
    }
}
