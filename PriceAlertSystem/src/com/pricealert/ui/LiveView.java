package com.pricealert.ui;

import com.pricealert.model.PriceTick;
import com.pricealert.monitor.PriceMonitor;
import com.pricealert.util.Fmt;

import java.util.List;

/** Formats one live-price line:  time  symbol  price  change-since-last-tick  change-since-start  sparkline */
public final class LiveView {
    private LiveView() { }

    public static String format(PriceTick t, PriceMonitor monitor) {
        List<Double> h = monitor.history(t.symbol());
        double prev = h.size() >= 2 ? h.get(h.size() - 2) : t.price();
        double diff = t.price() - prev;
        double start = monitor.sessionStart(t.symbol());
        double sinceStart = Double.isNaN(start) || start == 0 ? 0 : (t.price() - start) / start * 100.0;

        String arrow;
        if (diff > 0) arrow = Fmt.green((Spark.unicode ? "▲ " : "^ ") + Fmt.signedPrice(diff));
        else if (diff < 0) arrow = Fmt.red((Spark.unicode ? "▼ " : "v ") + Fmt.signedPrice(diff));
        else arrow = (Spark.unicode ? "▬ " : "- ") + "0.00";

        String since = sinceStart >= 0 ? Fmt.green(Fmt.pct(sinceStart)) : Fmt.red(Fmt.pct(sinceStart));
        return String.format("%s  %-24s %14s  %-22s %s  %s",
                Fmt.time(t.time()), t.symbol(), Fmt.bold(Fmt.price(t.price())), arrow, since, Spark.draw(h, 24));
    }
}
