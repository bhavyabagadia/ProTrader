package com.pricealert.provider;

import com.pricealert.model.PriceTick;
import com.pricealert.model.SymbolRef;
import com.pricealert.util.AppConfig;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Holds every provider by id and turns "binance:BTCUSDT" text into a fetch call on the right one. */
public class ProviderRegistry {
    private final Map<String, PriceProvider> providers = new LinkedHashMap<>();

    public void register(PriceProvider p) { providers.put(p.id(), p); }

    public Collection<PriceProvider> all() { return providers.values(); }

    public PriceProvider get(String id) { return providers.get(id); }

    /** The standard line-up used by the real program. */
    public static ProviderRegistry standard(AppConfig cfg, SimulatedProvider sim) {
        ProviderRegistry r = new ProviderRegistry();
        r.register(new BinanceProvider());
        r.register(new YahooProvider());
        r.register(new CoinGeckoProvider(CoinGeckoProvider.DEFAULT_BASE, cfg.coingeckoKey(), cfg.coingeckoCurrency()));
        r.register(new FinnhubProvider(FinnhubProvider.DEFAULT_BASE, cfg.finnhubKey()));
        r.register(sim);
        return r;
    }

    /**
     * Turns user text into a SymbolRef.
     *   "binance:BTCUSDT" -> as typed
     *   "BTCUSDT"         -> guessed: ends with USDT/BUSD -> binance
     *   "RELIANCE.NS"     -> guessed: has a dot, or starts with ^ -> yahoo
     *   "AAPL"            -> guessed: yahoo
     */
    public SymbolRef parse(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Symbol is empty");
        int colon = t.indexOf(':');
        if (colon > 0) {
            String provider = t.substring(0, colon).toLowerCase(Locale.ROOT);
            String ticker = t.substring(colon + 1).trim();
            if (!providers.containsKey(provider)) {
                throw new IllegalArgumentException("Unknown provider '" + provider + "'. Choose from: " + providers.keySet());
            }
            if (ticker.isEmpty()) throw new IllegalArgumentException("Ticker is empty after '" + provider + ":'");
            return new SymbolRef(provider, normalise(provider, ticker));
        }
        String upper = t.toUpperCase(Locale.ROOT);
        String guess = (upper.endsWith("USDT") || upper.endsWith("BUSD") || upper.endsWith("USDC")) ? "binance" : "yahoo";
        return new SymbolRef(guess, upper);
    }

    /** Fetch one price. The tick's symbol is set to the full key (provider:ticker). */
    public PriceTick fetch(SymbolRef ref) throws ProviderException {
        PriceProvider p = providers.get(ref.provider());
        if (p == null) throw new ProviderException("Unknown provider: " + ref.provider());
        PriceTick t = p.fetch(ref.ticker());
        return new PriceTick(ref.key(), t.price(), t.time() == null ? Instant.now() : t.time(), ref.provider());
    }

    /** Minimum seconds between requests for this symbol, to respect free-plan rate limits. */
    public int minIntervalSeconds(SymbolRef ref) {
        return switch (ref.provider()) {
            case "coingecko" -> ((CoinGeckoProvider) providers.get("coingecko")).status().startsWith("demo") ? 3 : 15;
            case "yahoo" -> 3;
            case "finnhub" -> 2;
            default -> 0;
        };
    }

    // CoinGecko ids are lower-case (bitcoin); Binance/Yahoo/Finnhub tickers are upper-case; sim keeps the case.
    private static String normalise(String provider, String ticker) {
        return switch (provider) {
            case "coingecko" -> ticker.toLowerCase(Locale.ROOT);
            case "binance", "finnhub", "yahoo", "sim" -> ticker.toUpperCase(Locale.ROOT);
            default -> ticker;
        };
    }
}
