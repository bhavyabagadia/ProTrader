package com.pricealert.provider;

import com.pricealert.model.PriceTick;
import com.pricealert.util.MiniJson;

import java.time.Instant;
import java.util.Map;

/**
 * Crypto prices from CoinGecko. Works without a key (low rate limit);
 * a FREE "Demo" key from coingecko.com/en/api raises the limit.
 * Ticker is the coin id: bitcoin, ethereum, solana, dogecoin ...
 *
 * Request : GET /api/v3/simple/price?ids=bitcoin&vs_currencies=usd
 * Reply   : {"bitcoin":{"usd":43000.12}}
 */
public class CoinGeckoProvider implements PriceProvider {
    public static final String DEFAULT_BASE = "https://api.coingecko.com";

    private final String baseUrl;
    private final String apiKey;
    private final String currency;

    public CoinGeckoProvider(String baseUrl, String apiKey, String currency) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.currency = currency;
    }

    @Override public String id() { return "coingecko"; }

    @Override public String label() { return "CoinGecko - coin ids such as bitcoin, ethereum, solana"; }

    @Override
    public String status() {
        return apiKey.isEmpty()
                ? "works without a key (low rate limit) - add coingecko.key for more"
                : "demo key loaded";
    }

    @Override
    public PriceTick fetch(String coinId) throws ProviderException {
        String url = baseUrl + "/api/v3/simple/price?ids=" + Http.enc(coinId) + "&vs_currencies=" + Http.enc(currency);
        Map<String, String> headers = apiKey.isEmpty() ? Map.of() : Map.of("x-cg-demo-api-key", apiKey);
        return parse(coinId, currency, Http.get(url, headers));
    }

    public static PriceTick parse(String coinId, String currency, String body) throws ProviderException {
        try {
            double price = MiniJson.asDouble(MiniJson.path(MiniJson.parse(body), coinId, currency));
            if (Double.isNaN(price) || price <= 0) {
                throw new ProviderException("CoinGecko does not know '" + coinId
                        + "' (use coin ids like bitcoin, ethereum, solana)");
            }
            return new PriceTick(coinId, price, Instant.now(), "coingecko");
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Unreadable reply from CoinGecko: " + e.getMessage(), e);
        }
    }
}
