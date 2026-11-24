package com.pricealert.provider;

import com.pricealert.model.PriceTick;
import com.pricealert.util.MiniJson;

import java.time.Instant;
import java.util.Map;

/**
 * Crypto prices from Binance's public market-data API. NO API KEY NEEDED.
 * Ticker example: BTCUSDT, ETHUSDT, SOLUSDT.
 *
 * Request : GET /api/v3/ticker/price?symbol=BTCUSDT
 * Reply   : {"symbol":"BTCUSDT","price":"43125.50000000"}
 */
public class BinanceProvider implements PriceProvider {
    public static final String DEFAULT_BASE = "https://data-api.binance.vision";

    private final String baseUrl;

    public BinanceProvider() { this(DEFAULT_BASE); }

    /** The base URL can be changed so the tests can point us at a fake local server. */
    public BinanceProvider(String baseUrl) { this.baseUrl = baseUrl; }

    @Override public String id() { return "binance"; }

    @Override public String label() { return "Binance - crypto pairs such as BTCUSDT, ETHUSDT (no key needed)"; }

    @Override
    public PriceTick fetch(String ticker) throws ProviderException {
        String body = Http.get(baseUrl + "/api/v3/ticker/price?symbol=" + Http.enc(ticker), Map.of());
        return parse(ticker, body);
    }

    /** Public and static so it can be tested without any internet. */
    public static PriceTick parse(String ticker, String body) throws ProviderException {
        try {
            double price = MiniJson.asDouble(MiniJson.path(MiniJson.parse(body), "price"));
            if (Double.isNaN(price) || price <= 0) {
                throw new ProviderException("Binance returned no price for " + ticker);
            }
            return new PriceTick(ticker, price, Instant.now(), "binance");
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Unreadable reply from Binance: " + e.getMessage(), e);
        }
    }
}
