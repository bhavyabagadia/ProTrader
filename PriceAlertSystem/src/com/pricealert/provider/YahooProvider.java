package com.pricealert.provider;

import com.pricealert.model.PriceTick;
import com.pricealert.util.MiniJson;

import java.time.Instant;
import java.util.Map;

/**
 * Stocks, indices, forex and crypto from Yahoo Finance's public chart endpoint. NO API KEY NEEDED.
 * Ticker examples: RELIANCE.NS, TCS.NS (NSE), 500325.BO (BSE), AAPL, MSFT, ^NSEI (Nifty 50), BTC-USD.
 * (This is an unofficial, free endpoint - fine for a college project, not for production.)
 *
 * Request : GET /v8/finance/chart/RELIANCE.NS?interval=1m&amp;range=1d
 * Reply   : {"chart":{"result":[{"meta":{"regularMarketPrice":2891.35,...}}],"error":null}}
 */
public class YahooProvider implements PriceProvider {
    public static final String DEFAULT_BASE = "https://query1.finance.yahoo.com";
    // Yahoo rejects requests that do not look like a browser.
    private static final String BROWSER_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final String baseUrl;

    public YahooProvider() { this(DEFAULT_BASE); }

    public YahooProvider(String baseUrl) { this.baseUrl = baseUrl; }

    @Override public String id() { return "yahoo"; }

    @Override public String label() { return "Yahoo Finance - stocks like RELIANCE.NS, TCS.NS, AAPL, ^NSEI (no key needed)"; }

    @Override
    public PriceTick fetch(String ticker) throws ProviderException {
        String url = baseUrl + "/v8/finance/chart/" + Http.enc(ticker) + "?interval=1m&range=1d";
        return parse(ticker, Http.get(url, Map.of("User-Agent", BROWSER_UA)));
    }

    public static PriceTick parse(String ticker, String body) throws ProviderException {
        try {
            Object root = MiniJson.parse(body);
            Object error = MiniJson.path(root, "chart", "error");
            if (error != null) {
                Object why = MiniJson.path(error, "description");
                throw new ProviderException("Yahoo says: " + (why == null ? "unknown symbol " + ticker : why));
            }
            double price = MiniJson.asDouble(MiniJson.path(root, "chart", "result", 0, "meta", "regularMarketPrice"));
            if (Double.isNaN(price) || price <= 0) {
                throw new ProviderException("Yahoo returned no price for " + ticker);
            }
            return new PriceTick(ticker, price, Instant.now(), "yahoo");
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Unreadable reply from Yahoo: " + e.getMessage(), e);
        }
    }
}
