package com.pricealert.alert;

import com.pricealert.model.Alert;
import com.pricealert.model.AlertEvent;
import com.pricealert.model.AlertType;
import com.pricealert.model.PriceTick;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the list of alerts and the watchlist, evaluates them against new prices,
 * and saves everything to a small text file so alerts survive closing the program.
 *
 * File format (tab separated, one record per line):
 *   A  id  symbol  type  value  base  high  active  createdMillis  triggeredMillis  triggeredPrice
 *   W  symbol
 */
public class AlertManager {
    private final List<Alert> alerts = new ArrayList<>();
    private final Set<String> watchlist = new LinkedHashSet<>();
    private final Path file;     // null = do not save (used by tests)
    private int nextId = 1;

    public AlertManager(Path file) {
        this.file = file;
    }

    // --------------------------------------------------------------- alerts

    public synchronized Alert add(String symbol, AlertType type, double value, double currentPrice) {
        Alert a = new Alert(nextId++, symbol, type, value, currentPrice);
        alerts.add(a);
        save();
        return a;
    }

    public synchronized boolean remove(int id) {
        boolean removed = alerts.removeIf(a -> a.id() == id);
        if (removed) save();
        return removed;
    }

    public synchronized Optional<Alert> find(int id) {
        return alerts.stream().filter(a -> a.id() == id).findFirst();
    }

    /** A snapshot copy, so callers can loop without worrying about other threads changing the list. */
    public synchronized List<Alert> list() { return new ArrayList<>(alerts); }

    public synchronized int activeCount() {
        return (int) alerts.stream().filter(Alert::active).count();
    }

    public synchronized boolean rearm(int id, double currentPrice) {
        Optional<Alert> a = find(id);
        a.ifPresent(x -> x.arm(currentPrice));
        if (a.isPresent()) save();
        return a.isPresent();
    }

    /** Give every active alert for this symbol the new price; collect the ones that fired. */
    public synchronized List<AlertEvent> onTick(PriceTick tick) {
        List<AlertEvent> fired = new ArrayList<>();
        for (Alert a : alerts) {
            if (!a.symbol().equals(tick.symbol())) continue;
            Optional<String> reason = a.check(tick.price());
            reason.ifPresent(r -> fired.add(new AlertEvent(a.id(), a.symbol(), tick.price(), r, Instant.now())));
        }
        // Save every time: trailing-stop high-water marks change even when nothing fires.
        // The file is tiny, so this is cheap. (A bigger program would save less often.)
        if (!alerts.isEmpty()) save();
        return fired;
    }

    // ------------------------------------------------------------ watchlist

    public synchronized boolean watch(String symbol) {
        boolean added = watchlist.add(symbol);
        if (added) save();
        return added;
    }

    public synchronized boolean unwatch(String symbol) {
        boolean removed = watchlist.remove(symbol);
        if (removed) save();
        return removed;
    }

    public synchronized List<String> watchlist() { return new ArrayList<>(watchlist); }

    /** Every symbol the monitor must fetch: the watchlist plus symbols of still-active alerts. */
    public synchronized Set<String> symbolsToPoll() {
        Set<String> s = new LinkedHashSet<>(watchlist);
        for (Alert a : alerts) if (a.active()) s.add(a.symbol());
        return s;
    }

    // ----------------------------------------------------------- save / load

    public synchronized void save() {
        if (file == null) return;
        List<String> lines = new ArrayList<>();
        for (String w : watchlist) lines.add("W\t" + w);
        for (Alert a : alerts) {
            lines.add(String.join("\t", "A",
                    String.valueOf(a.id()), a.symbol(), a.type().name(), String.valueOf(a.value()),
                    String.valueOf(a.basePrice()), String.valueOf(a.highWater()), String.valueOf(a.active()),
                    String.valueOf(a.created().toEpochMilli()),
                    String.valueOf(a.triggeredAt() == null ? 0 : a.triggeredAt().toEpochMilli()),
                    String.valueOf(a.triggeredPrice())));
        }
        try {
            // Write to a temp file, then rename: a crash mid-write can never leave a half-written save file.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Warning: could not save alerts to " + file + ": " + e.getMessage());
        }
    }

    /** Load the save file if it exists. Bad lines are skipped, not fatal. Returns how many alerts were loaded. */
    public synchronized int load() {
        if (file == null || !Files.isRegularFile(file)) return 0;
        int loaded = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                try {
                    if (f[0].equals("W") && f.length >= 2) {
                        watchlist.add(f[1]);
                    } else if (f[0].equals("A") && f.length >= 11) {
                        long trig = Long.parseLong(f[9]);
                        Alert a = new Alert(Integer.parseInt(f[1]), f[2], AlertType.valueOf(f[3]),
                                Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                                Boolean.parseBoolean(f[7]), Instant.ofEpochMilli(Long.parseLong(f[8])),
                                trig == 0 ? null : Instant.ofEpochMilli(trig), Double.parseDouble(f[10]));
                        alerts.add(a);
                        nextId = Math.max(nextId, a.id() + 1);
                        loaded++;
                    }
                } catch (RuntimeException bad) {
                    System.err.println("Skipping unreadable line in " + file + ": " + line);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
        return loaded;
    }
}
