package com.pricealert.model;

/**
 * Says WHICH provider to ask and WHAT to ask for.
 * Example: provider "binance", ticker "BTCUSDT"  ->  key() = "binance:BTCUSDT".
 */
public record SymbolRef(String provider, String ticker) {
    public String key() { return provider + ":" + ticker; }

    @Override
    public String toString() { return key(); }
}
