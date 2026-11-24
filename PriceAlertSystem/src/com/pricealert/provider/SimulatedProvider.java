package com.pricealert.provider;

import com.pricealert.model.PriceTick;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * A fake market that works with NO internet. Each ticker follows a "random walk":
 * every fetch multiplies the last price by (1 + a small random percentage).
 *
 * Why have it?  (1) demo in a classroom with bad Wi-Fi, (2) predictable automated tests,
 * (3) markets are closed at night/weekends but the demo still needs moving prices.
 *
 * Ticker can be anything: BTC, RELIANCE, AAPL ... unknown names get a stable made-up start price.
 */
public class SimulatedProvider implements PriceProvider {
    private static final Map<String, Double> START = Map.ofEntries(
            Map.entry("BTC", 95000.0), Map.entry("ETH", 3500.0), Map.entry("SOL", 180.0),
            Map.entry("RELIANCE", 2900.0), Map.entry("TCS", 4100.0), Map.entry("INFY", 1800.0),
            Map.entry("AAPL", 230.0), Map.entry("TSLA", 350.0), Map.entry("NIFTY", 24000.0));

    private final Map<String, Double> last = new HashMap<>();
    private final Random random;
    private final double volatilityPct; // typical size of one step, in percent

    public SimulatedProvider() { this(new Random(), 0.15); }

    /** Pass a seeded Random for repeatable output; a higher volatility makes alerts fire sooner. */
    public SimulatedProvider(Random random, double volatilityPct) {
        this.random = random;
        this.volatilityPct = volatilityPct;
    }

    @Override public String id() { return "sim"; }

    @Override public String label() { return "Simulator - fake moving prices, works offline (e.g. sim:BTC, sim:RELIANCE)"; }

    @Override public String status() { return "always ready (no internet needed)"; }

    @Override
    public synchronized PriceTick fetch(String ticker) {
        String key = ticker.toUpperCase(Locale.ROOT);
        double prev = last.computeIfAbsent(key, SimulatedProvider::startPrice);
        double stepPct = random.nextGaussian() * volatilityPct;
        double next = Math.max(0.01, prev * (1 + stepPct / 100.0));
        next = Math.round(next * 100.0) / 100.0;
        last.put(key, next);
        return new PriceTick(ticker, next, Instant.now(), "sim");
    }

    /** Jump a ticker straight to a price ("market shock"). Used by the demo and the tests. */
    public synchronized void forcePrice(String ticker, double price) {
        last.put(ticker.toUpperCase(Locale.ROOT), price);
    }

    private static double startPrice(String key) {
        Double known = START.get(key);
        if (known != null) return known;
        return 100 + Math.abs(key.hashCode() % 900); // stable value between 100 and 999
    }
}
