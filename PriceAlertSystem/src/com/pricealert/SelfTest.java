package com.pricealert;

import com.pricealert.alert.AlertManager;
import com.pricealert.model.Alert;
import com.pricealert.model.AlertEvent;
import com.pricealert.model.AlertType;
import com.pricealert.model.PriceTick;
import com.pricealert.model.SymbolRef;
import com.pricealert.monitor.PriceMonitor;
import com.pricealert.notify.Notifier;
import com.pricealert.notify.TelegramNotifier;
import com.pricealert.provider.BinanceProvider;
import com.pricealert.provider.CoinGeckoProvider;
import com.pricealert.provider.FinnhubProvider;
import com.pricealert.provider.ProviderException;
import com.pricealert.provider.ProviderRegistry;
import com.pricealert.provider.SimulatedProvider;
import com.pricealert.provider.YahooProvider;
import com.pricealert.util.AppConfig;
import com.pricealert.util.Fmt;
import com.pricealert.util.MiniJson;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Automated checks - no JUnit needed. Run:  java -cp out com.pricealert.SelfTest
 * Everything runs offline: the HTTP tests talk to a tiny fake server started inside this program.
 */
public class SelfTest {
    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Fmt.color = false;
        testJson();
        testFormatting();
        testProviderParsing();
        testAlertLogic();
        testRegistryParsing();
        testPersistence();
        testMonitorWithSimulator();
        testHttpLayer();

        System.out.println("\n" + passed + " checks passed, " + failures.size() + " failed.");
        failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ tests

    private static void testJson() {
        System.out.println("MiniJson");
        Object o = MiniJson.parse("{\"a\":{\"b\":[1,2.5,{\"c\":\"x\\ny\\u0041\"}]},\"t\":true,\"n\":null,\"neg\":-1.5e2}");
        check("nested path + escapes", "x\nyA".equals(MiniJson.path(o, "a", "b", 2, "c")));
        check("array index", MiniJson.asDouble(MiniJson.path(o, "a", "b", 1)) == 2.5);
        check("exponent number", MiniJson.asDouble(MiniJson.path(o, "neg")) == -150.0);
        check("boolean", Boolean.TRUE.equals(MiniJson.path(o, "t")));
        check("missing path gives null", MiniJson.path(o, "a", "zzz", 0) == null);
        check("string number -> double", MiniJson.asDouble("43125.50") == 43125.5);
        check("garbage -> NaN", Double.isNaN(MiniJson.asDouble("abc")));
        for (String bad : new String[]{"{", "[1,", "tru", "{\"a\" 1}", "{} extra", ""}) {
            boolean threw = false;
            try {
                MiniJson.parse(bad);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check("rejects bad JSON: '" + bad + "'", threw);
        }
    }

    private static void testFormatting() {
        System.out.println("Formatting");
        check("price big", Fmt.price(43125.5).equals("43,125.50"));
        check("price small coin keeps 8 decimals", Fmt.price(0.000123).equals("0.00012300"));
        check("price mid range 4 decimals", Fmt.price(12.3456789).equals("12.3457"));
        check("pct", Fmt.pct(1.5).equals("+1.500%"));
        check("plain 2.0", Fmt.plain(2.0).equals("2"));
        check("plain 0.25", Fmt.plain(0.25).equals("0.25"));
    }

    private static void testProviderParsing() throws Exception {
        System.out.println("Provider reply parsing");
        check("binance ok", BinanceProvider.parse("BTCUSDT", "{\"symbol\":\"BTCUSDT\",\"price\":\"43125.50000000\"}").price() == 43125.5);
        check("binance bad symbol", fails(() -> BinanceProvider.parse("XXX", "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}")));
        check("binance garbage", fails(() -> BinanceProvider.parse("X", "<html>")));

        check("coingecko ok", CoinGeckoProvider.parse("bitcoin", "usd", "{\"bitcoin\":{\"usd\":43000.12}}").price() == 43000.12);
        check("coingecko unknown coin", fails(() -> CoinGeckoProvider.parse("nope", "usd", "{}")));

        String yahoo = "{\"chart\":{\"result\":[{\"meta\":{\"symbol\":\"RELIANCE.NS\",\"regularMarketPrice\":2891.35}}],\"error\":null}}";
        check("yahoo ok", YahooProvider.parse("RELIANCE.NS", yahoo).price() == 2891.35);
        String yahooErr = "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No data found, symbol may be delisted\"}}}";
        check("yahoo error message", failsWith(() -> YahooProvider.parse("ZZZ", yahooErr), "No data found"));

        check("finnhub ok", FinnhubProvider.parse("AAPL", "{\"c\":189.95,\"d\":1.2,\"dp\":0.63}").price() == 189.95);
        check("finnhub unknown (c=0)", fails(() -> FinnhubProvider.parse("ZZZ", "{\"c\":0,\"d\":null}")));
    }

    private static void testAlertLogic() {
        System.out.println("Alert rules");
        Alert above = new Alert(1, "x", AlertType.ABOVE, 100, 90);
        check("ABOVE quiet below target", above.check(99.99).isEmpty() && above.active());
        check("ABOVE fires at target", above.check(100).isPresent() && !above.active());
        check("ABOVE one-shot (no second fire)", above.check(200).isEmpty());
        above.arm(150);
        check("re-arm makes it active again", above.active() && above.check(120).isPresent());

        Alert below = new Alert(2, "x", AlertType.BELOW, 50, 60);
        check("BELOW quiet above target", below.check(50.01).isEmpty());
        check("BELOW fires", below.check(49).isPresent());

        Alert pct = new Alert(3, "x", AlertType.PERCENT_MOVE, 2, 100);
        check("PERCENT quiet inside band", pct.check(101.9).isEmpty() && pct.check(98.1).isEmpty());
        check("PERCENT fires on drop", pct.check(97.9).isPresent());
        Alert pctUp = new Alert(4, "x", AlertType.PERCENT_MOVE, 2, 100);
        check("PERCENT fires on rise", pctUp.check(102.5).isPresent());

        Alert trail = new Alert(5, "x", AlertType.TRAILING_STOP, 5, 100);
        check("TRAIL high ratchets up", trail.check(110).isEmpty() && trail.highWater() == 110);
        check("TRAIL quiet above stop (104.5)", trail.check(105).isEmpty());
        check("TRAIL high never lowers", trail.highWater() == 110);
        check("TRAIL fires below stop", trail.check(104).isPresent());

        check("rejects zero value", fails(() -> new Alert(9, "x", AlertType.ABOVE, 0, 1)));
        check("rejects 100% trailing", fails(() -> new Alert(9, "x", AlertType.TRAILING_STOP, 100, 1)));
        check("wouldFireAt warns", new Alert(6, "x", AlertType.ABOVE, 100, 90).wouldFireAt(101));
    }

    private static void testRegistryParsing() {
        System.out.println("Symbol parsing");
        ProviderRegistry r = ProviderRegistry.standard(AppConfig.empty(), new SimulatedProvider());
        check("btcusdt -> binance", r.parse("btcusdt").equals(new SymbolRef("binance", "BTCUSDT")));
        check("reliance.ns -> yahoo", r.parse("reliance.ns").equals(new SymbolRef("yahoo", "RELIANCE.NS")));
        check("^nsei -> yahoo", r.parse("^nsei").equals(new SymbolRef("yahoo", "^NSEI")));
        check("coingecko lower-cases id", r.parse("coingecko:Bitcoin").equals(new SymbolRef("coingecko", "bitcoin")));
        check("explicit finnhub", r.parse("finnhub:aapl").equals(new SymbolRef("finnhub", "AAPL")));
        check("unknown provider rejected", fails(() -> r.parse("foo:BAR")));
        check("empty rejected", fails(() -> r.parse("  ")));
        check("empty ticker rejected", fails(() -> r.parse("binance:")));
    }

    private static void testPersistence() throws Exception {
        System.out.println("Save / load");
        Path f = Files.createTempFile("alerts", ".tsv");
        try {
            AlertManager m = new AlertManager(f);
            m.add("sim:BTC", AlertType.ABOVE, 100000, 95000);
            m.add("sim:ETH", AlertType.TRAILING_STOP, 3, 3500);
            Alert fired = m.add("sim:SOL", AlertType.BELOW, 100, 180);
            m.watch("binance:BTCUSDT");
            m.onTick(new PriceTick("sim:SOL", 90, Instant.now(), "sim")); // fires + saves

            AlertManager m2 = new AlertManager(f);
            check("loads 3 alerts", m2.load() == 3);
            check("watchlist restored", m2.watchlist().equals(List.of("binance:BTCUSDT")));
            check("fired state restored", !m2.find(fired.id()).orElseThrow().active()
                    && m2.find(fired.id()).orElseThrow().triggeredPrice() == 90);
            check("active state restored", m2.find(1).orElseThrow().active());
            check("ids continue after load", m2.add("sim:X", AlertType.ABOVE, 1, 0.5).id() == 4);
            check("polls only active + watched",
                    m2.symbolsToPoll().containsAll(List.of("sim:BTC", "sim:ETH", "binance:BTCUSDT"))
                            && !m2.symbolsToPoll().contains("sim:SOL"));
        } finally {
            Files.deleteIfExists(f);
        }
        // corrupt file must not crash
        Path bad = Files.createTempFile("bad", ".tsv");
        try {
            Files.writeString(bad, "A\tnot\tenough\nW\tsim:BTC\nrandom junk\n");
            AlertManager m3 = new AlertManager(bad);
            check("corrupt file skipped safely", m3.load() == 0 && m3.watchlist().equals(List.of("sim:BTC")));
        } finally {
            Files.deleteIfExists(bad);
        }
    }

    private static void testMonitorWithSimulator() {
        System.out.println("Monitor + alerts (simulator)");
        SimulatedProvider sim = new SimulatedProvider(new Random(1), 0.0); // 0 volatility = price only moves when we say
        ProviderRegistry r = ProviderRegistry.standard(AppConfig.empty(), sim);
        AlertManager alerts = new AlertManager(null);
        PriceMonitor monitor = new PriceMonitor(r, alerts, 1);
        List<AlertEvent> events = new CopyOnWriteArrayList<>();
        Notifier collector = events::add;
        monitor.addNotifier(collector);
        monitor.setMessageSink(m -> { });

        sim.forcePrice("BTC", 100);
        alerts.add("sim:BTC", AlertType.BELOW, 90, 100);
        monitor.pollOnce();
        check("no event while price is fine", events.isEmpty());
        check("latest price stored", monitor.latest("sim:BTC").price() == 100);

        sim.forcePrice("BTC", 89);
        monitor.pollOnce();
        check("event fires when price crosses", events.size() == 1 && events.get(0).price() == 89);
        monitor.pollOnce();
        monitor.pollOnce();
        check("one-shot: still exactly one event", events.size() == 1);
        check("fired alert no longer polled", alerts.symbolsToPoll().isEmpty());
        check("history recorded", monitor.history("sim:BTC").size() == 2);

        // a bad symbol must not break the loop or other symbols
        List<String> messages = new CopyOnWriteArrayList<>();
        monitor.setMessageSink(messages::add);
        alerts.watch("yahoo:"); // malformed on purpose
        alerts.watch("sim:ETH");
        monitor.pollOnce();
        check("bad symbol reported, good one still fetched",
                !messages.isEmpty() && monitor.latest("sim:ETH") != null);
        int before = messages.size();
        monitor.pollOnce();
        check("repeated identical error not spammed", messages.size() == before);

        // a notifier that crashes must not stop other notifiers
        List<AlertEvent> second = new CopyOnWriteArrayList<>();
        PriceMonitor m2 = new PriceMonitor(r, alerts, 1);
        m2.setMessageSink(messages::add);
        m2.addNotifier(e -> { throw new RuntimeException("boom"); });
        m2.addNotifier(second::add);
        alerts.add("sim:ETH", AlertType.ABOVE, 1, 1000); // already satisfied
        m2.pollOnce();
        check("crashing notifier isolated", second.size() == 1);
    }

    private static void testHttpLayer() throws Exception {
        System.out.println("HTTP layer (local fake server)");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> cgHeader = new AtomicReference<>();
        AtomicReference<String> fhHeader = new AtomicReference<>();
        AtomicReference<String> telegramBody = new AtomicReference<>();
        AtomicReference<String> yahooUa = new AtomicReference<>();

        server.createContext("/api/v3/ticker/price", ex -> reply(ex, 200,
                "{\"symbol\":\"" + query(ex, "symbol") + "\",\"price\":\"64000.10\"}"));
        server.createContext("/api/v3/simple/price", ex -> {
            cgHeader.set(ex.getRequestHeaders().getFirst("x-cg-demo-api-key"));
            reply(ex, 200, "{\"ethereum\":{\"usd\":3500.5}}");
        });
        server.createContext("/v8/finance/chart/", ex -> {
            yahooUa.set(ex.getRequestHeaders().getFirst("User-Agent"));
            reply(ex, 200, "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":2891.35}}],\"error\":null}}");
        });
        server.createContext("/api/v1/quote", ex -> {
            fhHeader.set(ex.getRequestHeaders().getFirst("X-Finnhub-Token"));
            reply(ex, 200, "{\"c\":189.95}");
        });
        server.createContext("/limited", ex -> reply(ex, 429, "slow down"));
        server.createContext("/denied", ex -> reply(ex, 403, "nope"));
        server.createContext("/boom", ex -> reply(ex, 500, "internal error"));
        server.createContext("/bottoken/sendMessage", ex -> {
            telegramBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "{\"ok\":true}");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            check("binance over HTTP", new BinanceProvider(base).fetch("BTCUSDT").price() == 64000.10);
            check("coingecko sends demo key header", new CoinGeckoProvider(base, "KEY123", "usd").fetch("ethereum").price() == 3500.5
                    && "KEY123".equals(cgHeader.get()));
            check("yahoo sends browser User-Agent", new YahooProvider(base).fetch("RELIANCE.NS").price() == 2891.35
                    && yahooUa.get() != null && yahooUa.get().startsWith("Mozilla"));
            check("finnhub sends token header", new FinnhubProvider(base, "FH_KEY").fetch("AAPL").price() == 189.95
                    && "FH_KEY".equals(fhHeader.get()));
            check("finnhub without key explains how to get one", failsWith(() -> new FinnhubProvider(base, "").fetch("AAPL"), "free API key"));

            check("HTTP 429 -> rate limit message", failsWith(() -> com.pricealert.provider.Http.get(base + "/limited", Map.of()), "Rate limit"));
            check("HTTP 403 -> key message", failsWith(() -> com.pricealert.provider.Http.get(base + "/denied", Map.of()), "API key"));
            check("HTTP 500 -> status in message", failsWith(() -> com.pricealert.provider.Http.get(base + "/boom", Map.of()), "HTTP 500"));
            check("unreachable server -> clean error", failsWith(() -> new BinanceProvider("http://127.0.0.1:1").fetch("X"), "Network problem"));

            new TelegramNotifier(base, "token", "12345").notify(
                    new AlertEvent(7, "binance:BTCUSDT", 100000, "price rose to 100,000.00 or above", Instant.now()));
            // the fake server only serves /bottoken/..., and TelegramNotifier calls base + /bot + token + /sendMessage
            check("telegram posts chat id + text", telegramBody.get() != null
                    && telegramBody.get().contains("chat_id=12345") && telegramBody.get().contains("ALERT+%237"));
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------- helpers

    private interface Risky { void run() throws Exception; }

    private static boolean fails(Risky r) {
        try {
            r.run();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean failsWith(Risky r, String textInMessage) {
        try {
            r.run();
            return false;
        } catch (Exception e) {
            return e.getMessage() != null && e.getMessage().contains(textInMessage);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failures.add(name);
            System.out.println("  FAIL " + name);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static String query(com.sun.net.httpserver.HttpExchange ex, String name) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return "";
        for (String part : q.split("&")) {
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        return "";
    }
}
