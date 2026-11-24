package com.pricealert.model;

import java.time.Instant;

/**
 * One price reading for one symbol at one moment.
 * A "record" is an immutable data holder - Java writes the constructor and getters for us.
 *
 * @param symbol the full symbol key, e.g. "binance:BTCUSDT"
 * @param price  latest traded price
 * @param time   when we received it
 * @param source which provider it came from, e.g. "binance"
 */
public record PriceTick(String symbol, double price, Instant time, String source) { }
