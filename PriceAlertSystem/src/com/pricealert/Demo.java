package com.pricealert;

import com.pricealert.alert.AlertManager;
import com.pricealert.model.AlertType;
import com.pricealert.model.PriceTick;
import com.pricealert.monitor.PriceMonitor;
import com.pricealert.notify.ConsoleNotifier;
import com.pricealert.notify.FileNotifier;
import com.pricealert.provider.ProviderRegistry;
import com.pricealert.provider.SimulatedProvider;
import com.pricealert.ui.LiveView;
import com.pricealert.util.AppConfig;
import com.pricealert.util.Fmt;

import java.nio.file.Path;
import java.util.Random;

/**
 * A fully automatic ~25 second showcase that needs NO internet and NO typing.
 * Great for a viva: run it, and every alert type fires on screen.
 * (A seeded Random and a few scripted "market shocks" make it behave the same every time.)
 */
public final class Demo {
    private Demo() { }

    public static void run() {
        System.out.println(Fmt.bold("\n  PRICE ALERT SYSTEM - AUTOMATIC DEMO (simulated market)\n"));

        SimulatedProvider sim = new SimulatedProvider(new Random(42), 0.12);
        ProviderRegistry registry = ProviderRegistry.standard(AppConfig.empty(), sim);
        AlertManager alerts = new AlertManager(null);   // null = demo does not touch your saved alerts
        PriceMonitor monitor = new PriceMonitor(registry, alerts, 1);
        monitor.addNotifier(new ConsoleNotifier());
        monitor.addNotifier(new FileNotifier(Path.of("demo_alerts_log.txt")));

        // Seed the first prices, then create one alert of every type.
        PriceTick btc = sim.fetch("BTC");
        PriceTick reliance = sim.fetch("RELIANCE");
        PriceTick eth = sim.fetch("ETH");
        PriceTick aapl = sim.fetch("AAPL");

        alerts.add("sim:BTC", AlertType.ABOVE, Math.round(btc.price() * 1.01), btc.price());
        alerts.add("sim:RELIANCE", AlertType.BELOW, Math.round(reliance.price() * 0.99), reliance.price());
        alerts.add("sim:ETH", AlertType.PERCENT_MOVE, 1.5, eth.price());
        alerts.add("sim:AAPL", AlertType.TRAILING_STOP, 1.0, aapl.price());

        System.out.println("Alerts created:");
        alerts.list().forEach(a -> System.out.println("  #" + a.id() + "  " + a.symbol() + "  " + a.describe()));
        System.out.println("\nWatching... (one price update per second)\n");

        monitor.setTickListener(t -> System.out.println(LiveView.format(t, monitor)));

        for (int second = 1; second <= 22; second++) {
            // Scripted "news events" so every alert type is guaranteed to fire.
            switch (second) {
                case 4 -> sim.forcePrice("AAPL", aapl.price() * 1.02);        // AAPL jumps 2% -> sets a new high
                case 7 -> sim.forcePrice("BTC", btc.price() * 1.015);          // BTC breaks above target
                case 10 -> sim.forcePrice("RELIANCE", reliance.price() * 0.98); // RELIANCE drops below target
                case 13 -> sim.forcePrice("ETH", eth.price() * 0.975);         // ETH falls 2.5% (> 1.5% move)
                case 16 -> sim.forcePrice("AAPL", aapl.price() * 1.02 * 0.985); // AAPL falls 1.5% from its high (> 1% stop)
                default -> { }
            }
            monitor.pollOnce();
            sleep(1000);
        }

        long fired = alerts.list().stream().filter(a -> !a.active()).count();
        System.out.println(Fmt.bold("\nDemo finished: " + fired + " of " + alerts.list().size() + " alerts fired."));
        System.out.println("Fired alerts were also written to demo_alerts_log.txt");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
