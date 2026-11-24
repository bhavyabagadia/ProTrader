package com.pricealert.model;

import com.pricealert.util.Fmt;

import java.time.Instant;
import java.util.Optional;

/**
 * One alert rule, e.g. "tell me when binance:BTCUSDT reaches 100,000".
 *
 * The important method is check(price): the monitor calls it for every new price, and it answers
 * "did this price just trigger me?". Alerts are ONE-SHOT: after firing they switch to inactive
 * (so you do not get 100 messages), and can be re-armed from the menu.
 *
 * All methods that read or change state are synchronized because the monitor thread (checking prices)
 * and the menu thread (listing / removing alerts) touch the same object at the same time.
 */
public final class Alert {
    private final int id;
    private final String symbol;
    private final AlertType type;
    private final double value;      // target price for ABOVE/BELOW, percent for PERCENT_MOVE/TRAILING_STOP

    private double basePrice;        // PERCENT_MOVE: the starting price to measure from
    private double highWater;        // TRAILING_STOP: highest price seen since the alert was armed
    private boolean active = true;
    private final Instant created;
    private Instant triggeredAt;
    private double triggeredPrice = Double.NaN;

    public Alert(int id, String symbol, AlertType type, double value, double startPrice) {
        this(id, symbol, type, value, Double.NaN, Double.NaN, true, Instant.now(), null, Double.NaN);
        arm(startPrice);
    }

    /** Full constructor, used when loading from the save file. */
    public Alert(int id, String symbol, AlertType type, double value, double basePrice, double highWater,
                 boolean active, Instant created, Instant triggeredAt, double triggeredPrice) {
        if (value <= 0 || Double.isNaN(value)) throw new IllegalArgumentException("Value must be a positive number");
        if ((type == AlertType.PERCENT_MOVE || type == AlertType.TRAILING_STOP) && value >= 100) {
            throw new IllegalArgumentException("Percent must be below 100");
        }
        this.id = id;
        this.symbol = symbol;
        this.type = type;
        this.value = value;
        this.basePrice = basePrice;
        this.highWater = highWater;
        this.active = active;
        this.created = created;
        this.triggeredAt = triggeredAt;
        this.triggeredPrice = triggeredPrice;
    }

    /** (Re)start tracking from this price: sets the percent-move baseline and the trailing-stop high. */
    public synchronized void arm(double currentPrice) {
        this.basePrice = currentPrice;
        this.highWater = currentPrice;
        this.active = true;
        this.triggeredAt = null;
        this.triggeredPrice = Double.NaN;
    }

    /**
     * Feed one new price in. Returns a plain-English reason if the alert fires, otherwise empty.
     */
    public synchronized Optional<String> check(double price) {
        if (!active) return Optional.empty();
        String reason = null;
        switch (type) {
            case ABOVE -> {
                if (price >= value) reason = "price rose to " + Fmt.price(value) + " or above";
            }
            case BELOW -> {
                if (price <= value) reason = "price fell to " + Fmt.price(value) + " or below";
            }
            case PERCENT_MOVE -> {
                if (!Double.isNaN(basePrice)) {
                    double move = (price - basePrice) / basePrice * 100.0;
                    if (Math.abs(move) >= value) {
                        reason = "price moved " + Fmt.pct(move) + " from the starting price " + Fmt.price(basePrice);
                    }
                }
            }
            case TRAILING_STOP -> {
                if (Double.isNaN(highWater) || price > highWater) highWater = price; // ratchet up, never down
                double stop = highWater * (1 - value / 100.0);
                if (price <= stop) {
                    reason = "price fell " + Fmt.plain(value) + "% from its high of " + Fmt.price(highWater)
                            + " (stop level " + Fmt.price(stop) + ")";
                }
            }
        }
        if (reason == null) return Optional.empty();
        active = false;
        triggeredAt = Instant.now();
        triggeredPrice = price;
        return Optional.of(reason);
    }

    /** Short description of the rule for the menu table. */
    public synchronized String describe() {
        return switch (type) {
            case ABOVE -> "rises to >= " + Fmt.price(value);
            case BELOW -> "falls to <= " + Fmt.price(value);
            case PERCENT_MOVE -> "moves +/-" + Fmt.plain(value) + "% from " + Fmt.price(basePrice);
            case TRAILING_STOP -> "falls " + Fmt.plain(value) + "% from high (high now "
                    + Fmt.price(highWater) + ", stop " + Fmt.price(highWater * (1 - value / 100.0)) + ")";
        };
    }

    public int id() { return id; }

    public String symbol() { return symbol; }

    public AlertType type() { return type; }

    public double value() { return value; }

    public synchronized double basePrice() { return basePrice; }

    public synchronized double highWater() { return highWater; }

    public synchronized boolean active() { return active; }

    public Instant created() { return created; }

    public synchronized Instant triggeredAt() { return triggeredAt; }

    public synchronized double triggeredPrice() { return triggeredPrice; }

    /** Would this alert fire immediately at this price? Used to warn the user while creating it. */
    public boolean wouldFireAt(double price) {
        return switch (type) {
            case ABOVE -> price >= value;
            case BELOW -> price <= value;
            default -> false;
        };
    }
}
