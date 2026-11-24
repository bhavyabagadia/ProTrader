package com.pricealert.provider;

import com.pricealert.model.PriceTick;

/**
 * Anything that can answer "what is the price of X right now?".
 * Binance, Yahoo, CoinGecko, Finnhub and the simulator all implement this one interface,
 * so the rest of the program never needs to know which one it is talking to (polymorphism).
 */
public interface PriceProvider {

    /** Short id used in symbols, e.g. "binance" in "binance:BTCUSDT". */
    String id();

    /** One-line description shown in the menu. */
    String label();

    /** Fetch the latest price. The returned tick's symbol is just the ticker; the registry adds the provider id. */
    PriceTick fetch(String ticker) throws ProviderException;

    /** Is the provider ready to use (API key present etc.)? */
    default String status() { return "ready (no key needed)"; }
}
