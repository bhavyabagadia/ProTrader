package com.pricealert.provider;

import com.pricealert.model.PriceTick;
import com.pricealert.util.MiniJson;

import java.time.Instant;
import java.util.Map;

/**
 * US stock quotes from Finnhub. Needs a FREE API key (register at https://finnhub.io/register).
 * The free plan covers US stocks such as AAPL, MSFT, TSLA.
 *
 * Request : GET /api/v1/quote?symbol=AAPL      (key sent in the X-Finnhub-Token header)
 * Reply   : {"c":189.95,"d":1.2,"dp":0.63,...}   where "c" is the current price
 */
public class FinnhubProvider implements PriceProvider {
    public static final String DEFAULT_BASE = "https://finnhub.io";

    private final String baseUrl;
    private final String apiKey;

    public FinnhubProvider(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    @Override public String id() { return "finnhub"; }

    @Override public String label() { return "Finnhub - US stocks such as AAPL, MSFT (free key needed)"; }

    @Override
    public String status() {
        return apiKey.isEmpty() ? "NO KEY - get a free one at https://finnhub.io/register" : "key loaded";
    }

    @Override
    public PriceTick fetch(String ticker) throws ProviderException {
        if (apiKey.isEmpty()) {
            throw new ProviderException("Finnhub needs a free API key: register at https://finnhub.io/register "
                    + "and put it in config.properties as finnhub.key=...");
        }
        String url = baseUrl + "/api/v1/quote?symbol=" + Http.enc(ticker);
        return parse(ticker, Http.get(url, Map.of("X-Finnhub-Token", apiKey)));
    }

    public static PriceTick parse(String ticker, String body) throws ProviderException {
        try {
            double price = MiniJson.asDouble(MiniJson.path(MiniJson.parse(body), "c"));
            // Finnhub answers c = 0 for symbols it does not know.
            if (Double.isNaN(price) || price <= 0) {
                throw new ProviderException("Finnhub has no quote for '" + ticker + "' (free plan = US stocks)");
            }
            return new PriceTick(ticker, price, Instant.now(), "finnhub");
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Unreadable reply from Finnhub: " + e.getMessage(), e);
        }
    }
}
