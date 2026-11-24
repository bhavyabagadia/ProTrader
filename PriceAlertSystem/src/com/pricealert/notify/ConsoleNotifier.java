package com.pricealert.notify;

import com.pricealert.model.AlertEvent;
import com.pricealert.util.Fmt;

/** Prints a highlighted box in the terminal and rings the terminal bell. */
public class ConsoleNotifier implements Notifier {
    @Override
    public void notify(AlertEvent e) {
        String bar = "=".repeat(64);
        // Build the whole message first and print it in ONE call, so two threads can never interleave half-lines.
        String msg = "\n" + Fmt.yellow(bar) + "\n"
                + Fmt.bold(Fmt.red("  !! ALERT #" + e.alertId() + " TRIGGERED !!")) + "  " + Fmt.stamp(e.time()) + "\n"
                + "  Symbol : " + e.symbol() + "\n"
                + "  Price  : " + Fmt.bold(Fmt.price(e.price())) + "\n"
                + "  Reason : " + e.condition() + "\n"
                + Fmt.yellow(bar) + "\n\u0007";
        System.out.println(msg);
    }
}
