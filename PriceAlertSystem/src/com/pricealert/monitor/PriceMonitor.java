package com.pricealert.monitor;

import com.pricealert.alert.AlertManager;
import com.pricealert.model.AlertEvent;
import com.pricealert.model.PriceTick;
import com.pricealert.model.SymbolRef;
import com.pricealert.notify.Notifier;
import com.pricealert.provider.ProviderException;
import com.pricealert.provider.ProviderRegistry;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The heart of the program: a BACKGROUND THREAD that wakes up every few seconds, fetches the price of
 * every symbol we care about, remembers it, shows it (if the live view is on) and checks the alerts.
 *
 * The menu runs on the main thread, the monitor on its own thread, so alerts fire while you sit at the menu.
 */
public class PriceMonitor {
    private static final int HISTORY_SIZE = 40;

    private final ProviderRegistry registry;
    private final AlertManager alerts;
    private final List<Notifier> notifiers = new ArrayList<>();

    // These maps are read by the menu thread and written by the monitor thread, so they must be thread-safe.
    private final Map<String, PriceTick> latest = new ConcurrentHashMap<>();
    private final Map<String, Double> sessionStart = new ConcurrentHashMap<>();
    private final Map<String, Deque<Double>> history = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFetchMillis = new ConcurrentHashMap<>();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();
    private final Map<String, Integer> failCount = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "price-monitor");
        t.setDaemon(true); // a daemon thread never stops the program from exiting
        return t;
    });
    private ScheduledFuture<?> task;
    private volatile int pollSeconds;
    private volatile Consumer<PriceTick> tickListener;   // live view hook (null = off)
    private volatile Consumer<String> messageSink = System.err::println;

    public PriceMonitor(ProviderRegistry registry, AlertManager alerts, int pollSeconds) {
        this.registry = registry;
        this.alerts = alerts;
        this.pollSeconds = Math.max(1, pollSeconds);
    }

    public void addNotifier(Notifier n) { notifiers.add(n); }

    public void setTickListener(Consumer<PriceTick> l) { this.tickListener = l; }

    public void setMessageSink(Consumer<String> sink) { this.messageSink = sink; }

    public int pollSeconds() { return pollSeconds; }

    // ------------------------------------------------------------ start / stop

    public synchronized void start() {
        if (task != null) return;
        task = scheduler.scheduleWithFixedDelay(this::safePoll, 0, pollSeconds, TimeUnit.SECONDS);
    }

    public synchronized void setPollSeconds(int seconds) {
        this.pollSeconds = Math.max(1, seconds);
        if (task != null) {
            task.cancel(false);
            task = scheduler.scheduleWithFixedDelay(this::safePoll, 0, pollSeconds, TimeUnit.SECONDS);
        }
    }

    public synchronized void stop() {
        if (task != null) task.cancel(false);
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ polling

    /**
     * IMPORTANT: if a scheduled task throws, the scheduler silently CANCELS all future runs.
     * So we catch everything here, otherwise one bad reply would kill monitoring forever.
     */
    private void safePoll() {
        try {
            pollOnce();
        } catch (Throwable t) {
            messageSink.accept("Monitor error (will keep running): " + t);
        }
    }

    /** One full cycle over all symbols. Public so tests and the demo can call it directly. */
    public void pollOnce() {
        for (String key : alerts.symbolsToPoll()) {
            SymbolRef ref;
            try {
                ref = registry.parse(key);
            } catch (IllegalArgumentException e) {
                reportError(key, e.getMessage());
                continue;
            }
            // Respect free-plan rate limits: skip if we asked this symbol too recently.
            long now = System.currentTimeMillis();
            Long last = lastFetchMillis.get(key);
            if (last != null && now - last < registry.minIntervalSeconds(ref) * 1000L) continue;
            lastFetchMillis.put(key, now);

            try {
                PriceTick tick = registry.fetch(ref);
                clearError(key);
                accept(tick);
            } catch (ProviderException e) {
                reportError(key, e.getMessage());
            }
        }
    }

    /**
     * Feed in a price we ALREADY fetched (e.g. while validating a new symbol) so the user sees it
     * straight away instead of waiting for the next cycle. Also counts as a fetch for rate-limit purposes.
     */
    public void seed(PriceTick tick) {
        lastFetchMillis.put(tick.symbol(), System.currentTimeMillis());
        accept(tick);
    }

    /** Store a new price, update the live view, and run the alert checks. */
    public void accept(PriceTick tick) {
        String key = tick.symbol();
        latest.put(key, tick);
        sessionStart.putIfAbsent(key, tick.price());
        Deque<Double> h = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (h) {
            h.addLast(tick.price());
            while (h.size() > HISTORY_SIZE) h.removeFirst();
        }

        Consumer<PriceTick> listener = tickListener;
        if (listener != null) listener.accept(tick);

        for (AlertEvent event : alerts.onTick(tick)) {
            for (Notifier n : notifiers) {
                try {
                    n.notify(event);
                } catch (RuntimeException e) {
                    messageSink.accept("Notifier failed: " + e);
                }
            }
        }
    }

    // ------------------------------------------------------------ error handling

    private void reportError(String key, String message) {
        int n = failCount.merge(key, 1, Integer::sum);
        String prev = lastError.put(key, message);
        // Tell the user once per distinct problem, then a reminder every 10th failure (no log spam).
        if (!message.equals(prev) || n % 10 == 0) {
            messageSink.accept("[" + key + "] " + message + (n > 1 ? "  (failed " + n + " times in a row)" : ""));
        }
    }

    private void clearError(String key) {
        if (lastError.remove(key) != null) {
            messageSink.accept("[" + key + "] recovered - prices are flowing again");
        }
        failCount.remove(key);
    }

    // ------------------------------------------------------------------ queries

    public PriceTick latest(String key) { return latest.get(key); }

    public Map<String, PriceTick> allLatest() { return Collections.unmodifiableMap(latest); }

    public double sessionStart(String key) { return sessionStart.getOrDefault(key, Double.NaN); }

    public List<Double> history(String key) {
        Deque<Double> h = history.get(key);
        if (h == null) return List.of();
        synchronized (h) {
            return new ArrayList<>(h);
        }
    }

    public Instant lastUpdate() {
        return latest.values().stream().map(PriceTick::time).max(Instant::compareTo).orElse(null);
    }
}
