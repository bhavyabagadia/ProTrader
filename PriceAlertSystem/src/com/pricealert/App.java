package com.pricealert;

import com.pricealert.alert.AlertManager;
import com.pricealert.model.Alert;
import com.pricealert.model.AlertType;
import com.pricealert.model.PriceTick;
import com.pricealert.model.SymbolRef;
import com.pricealert.monitor.PriceMonitor;
import com.pricealert.notify.ConsoleNotifier;
import com.pricealert.notify.FileNotifier;
import com.pricealert.notify.TelegramNotifier;
import com.pricealert.provider.PriceProvider;
import com.pricealert.provider.ProviderException;
import com.pricealert.provider.ProviderRegistry;
import com.pricealert.provider.SimulatedProvider;
import com.pricealert.ui.LiveView;
import com.pricealert.ui.Spark;
import com.pricealert.util.AppConfig;
import com.pricealert.util.Fmt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Crypto / Stock Price Alert System - menu program.
 *
 * Run:   java -cp out com.pricealert.App            (normal)
 *        java -cp out com.pricealert.App --demo     (30-second automatic demo, no internet needed)
 *        java -cp out com.pricealert.App --check    (test every live data source and exit)
 *        add --plain if your console shows strange characters
 */
public class App {
    /** Thrown when the input stream ends (Ctrl+D / piped input finished) so we can exit cleanly. */
    private static final class EndOfInput extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final ProviderRegistry registry;
    private final AlertManager alerts;
    private final PriceMonitor monitor;

    private App(AppConfig cfg, boolean realMode) {
        this.registry = ProviderRegistry.standard(cfg, new SimulatedProvider());
        this.alerts = new AlertManager(Path.of("alerts.tsv"));
        int loaded = alerts.load();
        this.monitor = new PriceMonitor(registry, alerts, cfg.pollSeconds());
        monitor.addNotifier(new ConsoleNotifier());
        monitor.addNotifier(new FileNotifier(Path.of("alerts_log.txt")));
        if (!cfg.telegramToken().isEmpty() && !cfg.telegramChat().isEmpty()) {
            monitor.addNotifier(new TelegramNotifier(TelegramNotifier.DEFAULT_BASE, cfg.telegramToken(), cfg.telegramChat()));
            System.out.println("Telegram notifications: ON");
        }
        if (loaded > 0) System.out.println("Loaded " + loaded + " saved alert(s) from alerts.tsv");
    }

    // ================================================================== main

    public static void main(String[] args) {
        List<String> a = List.of(args);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        boolean plain = a.contains("--plain") || (windows && !a.contains("--color"));
        Fmt.color = !plain;
        Spark.unicode = !plain;

        AppConfig cfg = new AppConfig(Path.of("config.properties"));

        if (a.contains("--demo")) {
            Demo.run();
            return;
        }
        App app = new App(cfg, true);
        if (a.contains("--check")) {
            app.testProviders();
            return;
        }
        app.run();
    }

    // ============================================================= main loop

    private void run() {
        Runtime.getRuntime().addShutdownHook(new Thread(alerts::save)); // save even on Ctrl+C
        monitor.start();
        System.out.println(Fmt.bold("\n  CRYPTO / STOCK PRICE ALERT SYSTEM"));
        System.out.println("  Background monitor started: checking prices every " + monitor.pollSeconds() + "s.");
        try {
            while (true) {
                printMenu();
                String choice = ask("Choose").trim();
                switch (choice) {
                    case "1" -> addAlert();
                    case "2" -> listAlerts();
                    case "3" -> removeAlert();
                    case "4" -> rearmAlert();
                    case "5" -> watchlistMenu();
                    case "6" -> liveView();
                    case "7" -> quickPrice();
                    case "8" -> testProviders();
                    case "9" -> setInterval();
                    case "0", "q", "quit", "exit" -> {
                        System.out.println("Goodbye! Alerts are saved in alerts.tsv.");
                        return;
                    }
                    default -> System.out.println("Please type a number from the menu.");
                }
            }
        } catch (EndOfInput e) {
            System.out.println("\nInput closed - exiting.");
        } finally {
            monitor.stop();
            alerts.save();
        }
    }

    private void printMenu() {
        System.out.println("\n--------------------------------------------------");
        System.out.println(" 1. Add alert            6. Live prices");
        System.out.println(" 2. List alerts          7. Quick price check");
        System.out.println(" 3. Remove alert         8. Test data sources");
        System.out.println(" 4. Re-arm alert         9. Change poll interval");
        System.out.println(" 5. Watchlist            0. Exit");
        System.out.println(" Active alerts: " + alerts.activeCount() + "   Poll: every " + monitor.pollSeconds() + "s");
        System.out.println("--------------------------------------------------");
    }

    // ============================================================== actions

    private void addAlert() {
        System.out.println("\nAlert types:");
        AlertType[] types = AlertType.values();
        for (int i = 0; i < types.length; i++) System.out.println("  " + (i + 1) + ". " + types[i].label());
        int t = (int) askNumber("Type (1-" + types.length + ")");
        if (t < 1 || t > types.length) { System.out.println("Not a valid type."); return; }
        AlertType type = types[t - 1];

        printSymbolHelp();
        SymbolRef ref = askSymbol();
        if (ref == null) return;
        PriceTick now = fetchOrComplain(ref);
        if (now == null) return;
        System.out.println("Current price of " + ref.key() + ": " + Fmt.bold(Fmt.price(now.price())));

        boolean percent = type == AlertType.PERCENT_MOVE || type == AlertType.TRAILING_STOP;
        double value = askNumber(percent ? "Percent (e.g. 2 for 2%)" : "Target price");
        if (Double.isNaN(value)) { System.out.println("That is not a number."); return; }

        try {
            Alert a = alerts.add(ref.key(), type, value, now.price());
            System.out.println(Fmt.green("Alert #" + a.id() + " created: " + a.symbol() + " " + a.describe()));
            if (a.wouldFireAt(now.price())) {
                System.out.println(Fmt.yellow("Note: the current price already satisfies this alert, so it fires right away."));
            }
            monitor.seed(now); // show the price in the list immediately and evaluate the new alert now
        } catch (IllegalArgumentException e) {
            System.out.println("Could not create alert: " + e.getMessage());
        }
    }

    private void listAlerts() {
        List<Alert> list = alerts.list();
        if (list.isEmpty()) { System.out.println("\nNo alerts yet. Choose 1 to add one."); return; }
        System.out.println();
        System.out.printf("%-4s %-10s %-24s %14s  %s%n", "ID", "STATUS", "SYMBOL", "PRICE NOW", "RULE");
        for (Alert a : list) {
            PriceTick t = monitor.latest(a.symbol());
            String price = t == null ? "waiting..." : Fmt.price(t.price());
            // Pad to width FIRST, then add colour codes (colour codes are invisible but would break the padding).
            String padded = String.format("%-10s", a.active() ? "ACTIVE" : "FIRED");
            String status = a.active() ? Fmt.green(padded) : Fmt.yellow(padded);
            System.out.printf("%-4d %s %-24s %14s  %s%n", a.id(), status, a.symbol(), price, a.describe());
            if (!a.active() && a.triggeredAt() != null) {
                System.out.println("       fired at " + Fmt.stamp(a.triggeredAt()) + " when price was " + Fmt.price(a.triggeredPrice()));
            }
        }
    }

    private void removeAlert() {
        listAlerts();
        if (alerts.list().isEmpty()) return;
        int id = (int) askNumber("Alert ID to remove");
        System.out.println(alerts.remove(id) ? "Removed alert #" + id : "No alert with that ID.");
    }

    private void rearmAlert() {
        listAlerts();
        if (alerts.list().isEmpty()) return;
        int id = (int) askNumber("Alert ID to re-arm");
        Alert a = alerts.find(id).orElse(null);
        if (a == null) { System.out.println("No alert with that ID."); return; }
        PriceTick now;
        try {
            now = registry.fetch(registry.parse(a.symbol()));
        } catch (ProviderException | IllegalArgumentException e) {
            System.out.println("Could not get a current price to start from: " + e.getMessage());
            return;
        }
        alerts.rearm(id, now.price());
        System.out.println("Alert #" + id + " is active again (measuring from " + Fmt.price(now.price()) + ").");
        monitor.seed(now);
    }

    private void watchlistMenu() {
        List<String> w = alerts.watchlist();
        System.out.println("\nWatchlist: " + (w.isEmpty() ? "(empty)" : String.join(", ", w)));
        String c = ask("a = add, r = remove, Enter = back").trim().toLowerCase(Locale.ROOT);
        if (c.equals("a")) {
            printSymbolHelp();
            SymbolRef ref = askSymbol();
            if (ref == null) return;
            PriceTick first = fetchOrComplain(ref);
            if (first == null) return;
            boolean added = alerts.watch(ref.key());
            System.out.println(added ? "Added " + ref.key() + " (now " + Fmt.price(first.price()) + ")" : "Already on the watchlist.");
            if (added) monitor.seed(first);
        } else if (c.equals("r")) {
            String s = ask("Symbol to remove (exactly as shown)").trim();
            System.out.println(alerts.unwatch(s) ? "Removed." : "Not on the watchlist.");
        }
    }

    private void liveView() {
        if (alerts.symbolsToPoll().isEmpty()) {
            System.out.println("Nothing to show yet - add a watchlist symbol (5) or an alert (1) first.");
            return;
        }
        System.out.println("\nLIVE PRICES - press Enter to stop. (new line every " + monitor.pollSeconds() + "s)\n");
        monitor.allLatest().values().forEach(t -> System.out.println(LiveView.format(t, monitor)));
        monitor.setTickListener(t -> System.out.println(LiveView.format(t, monitor)));
        try {
            ask("");
        } finally {
            monitor.setTickListener(null);
        }
    }

    private void quickPrice() {
        printSymbolHelp();
        SymbolRef ref = askSymbol();
        if (ref == null) return;
        PriceTick t = fetchOrComplain(ref);
        if (t != null) System.out.println(ref.key() + " = " + Fmt.bold(Fmt.price(t.price())) + "  (" + Fmt.stamp(t.time()) + ")");
    }

    /** Tries one sample symbol on each data source so you can see which ones work on this network. */
    private void testProviders() {
        System.out.println("\nTesting data sources (each does one real request)...\n");
        String[][] samples = {{"binance", "BTCUSDT"}, {"yahoo", "RELIANCE.NS"}, {"coingecko", "bitcoin"},
                {"finnhub", "AAPL"}, {"sim", "BTC"}};
        for (String[] s : samples) {
            PriceProvider p = registry.get(s[0]);
            long t0 = System.currentTimeMillis();
            String result;
            try {
                PriceTick t = registry.fetch(new SymbolRef(s[0], s[1]));
                result = Fmt.green("OK   ") + s[1] + " = " + Fmt.price(t.price()) + "  (" + (System.currentTimeMillis() - t0) + " ms)";
            } catch (ProviderException e) {
                result = Fmt.red("FAIL ") + e.getMessage();
            }
            System.out.printf("%-10s %s%n%10s status: %s%n", p.id(), result, "", p.status());
        }
    }

    private void setInterval() {
        double s = askNumber("New poll interval in seconds (current " + monitor.pollSeconds() + ")");
        if (Double.isNaN(s) || s < 1) { System.out.println("Please enter a whole number of seconds, 1 or more."); return; }
        monitor.setPollSeconds((int) s);
        System.out.println("Now checking every " + monitor.pollSeconds() + "s. (Free APIs may rate-limit below ~5s.)");
    }

    // ============================================================== helpers

    private void printSymbolHelp() {
        System.out.println("\nSymbol examples (provider:ticker, or just the ticker and I will guess):");
        for (PriceProvider p : registry.all()) System.out.println("  " + p.id() + " -> " + p.label());
    }

    private SymbolRef askSymbol() {
        String text = ask("Symbol").trim();
        try {
            return registry.parse(text);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            return null;
        }
    }

    private PriceTick fetchOrComplain(SymbolRef ref) {
        try {
            return registry.fetch(ref);
        } catch (ProviderException e) {
            System.out.println(Fmt.red("Could not get a price for " + ref.key() + ": ") + e.getMessage());
            return null;
        }
    }

    /** Reads a number; returns NaN if the text is not a number. Commas are allowed (95,000). */
    private double askNumber(String prompt) {
        String s = ask(prompt).trim().replace(",", "");
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private String ask(String prompt) {
        if (!prompt.isEmpty()) System.out.print(prompt + "> ");
        System.out.flush();
        try {
            String line = in.readLine();
            if (line == null) throw new EndOfInput();
            return line;
        } catch (IOException e) {
            throw new EndOfInput();
        }
    }
}
