
package com.pricealert;

import com.pricealert.alert.AlertManager;
import com.pricealert.model.Alert;
import com.pricealert.model.AlertType;
import com.pricealert.model.PriceTick;
import com.pricealert.model.SymbolRef;
import com.pricealert.monitor.PriceMonitor;
import com.pricealert.notify.ConsoleNotifier;
import com.pricealert.notify.FileNotifier;
import com.pricealert.notify.TelegramNotifier;
import com.pricealert.provider.ProviderException;
import com.pricealert.provider.ProviderRegistry;
import com.pricealert.util.AppConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProTraderServer {

    private final ProviderRegistry registry;
    private final AlertManager alerts;
    private final PriceMonitor monitor;
    private HttpServer server;

    public ProTraderServer() {
        AppConfig cfg = new AppConfig(Path.of("config.properties"));

        registry = ProviderRegistry.standard(
                cfg, new com.pricealert.provider.SimulatedProvider());

        alerts = new AlertManager(Path.of("alerts.tsv"));
        alerts.load();

        monitor = new PriceMonitor(
                registry, alerts, cfg.pollSeconds());

        monitor.addNotifier(new ConsoleNotifier());
        monitor.addNotifier(new FileNotifier(Path.of("alerts_log.txt")));

        if (!cfg.telegramToken().isEmpty()
                && !cfg.telegramChat().isEmpty()) {
            monitor.addNotifier(new TelegramNotifier(
                    TelegramNotifier.DEFAULT_BASE,
                    cfg.telegramToken(),
                    cfg.telegramChat()));
            System.out.println("Telegram notifications: ON");
        }
    }

    public void start(int port) throws IOException {

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", port), 0);

        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());

        monitor.start();
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            monitor.stop();
            alerts.save();
            if (server != null) server.stop(1);
        }));

        System.out.println();
        System.out.println("  ProTrader is running at http://localhost:" + port);
        System.out.println("  Keep this terminal open while using the dashboard.");
        System.out.println("  Press Ctrl+C to stop.");

        try {
            java.awt.Desktop.getDesktop().browse(
                    java.net.URI.create("http://localhost:" + port));
        } catch (Exception ignored) {
            // Open the URL manually if necessary.
        }
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            String method = x.getRequestMethod();

            if (path.equals("/") || path.equals("/index.html")) {
                send(x, 200, "text/html; charset=utf-8", PAGE);
                return;
            }

            if (path.startsWith("/api/")) {
                x.getResponseHeaders().set("Cache-Control", "no-store");

                if (path.equals("/api/state") && method.equals("GET")) {
                    sendJson(x, 200, state());

                } else if (path.equals("/api/alerts")
                        && method.equals("POST")) {

                    Map<String, String> b = body(x);

                    SymbolRef ref = registry.parse(required(b, "symbol"));
                    PriceTick tick = registry.fetch(ref);
                    monitor.seed(tick);

                    AlertType type = AlertType.valueOf(required(b, "type"));
                    double value = positive(b.get("value"));

                    Alert a = alerts.add(
                            ref.key(), type, value, tick.price());

                    sendJson(x, 201,
                            "{\"ok\":true,\"id\":" + a.id() + "}");

                } else if (path.equals("/api/alerts")
                        && method.equals("DELETE")) {

                    int id = Integer.parseInt(
                            query(x).getOrDefault("id", "0"));

                    sendJson(x, 200,
                            "{\"ok\":" + alerts.remove(id) + "}");

                } else if (path.equals("/api/rearm")
                        && method.equals("POST")) {

                    Map<String, String> b = body(x);
                    int id = Integer.parseInt(required(b, "id"));

                    Alert a = alerts.find(id).orElseThrow(
                            () -> new IllegalArgumentException("Alert not found"));

                    PriceTick tick = registry.fetch(
                            registry.parse(a.symbol()));

                    monitor.seed(tick);

                    sendJson(x, 200,
                            "{\"ok\":" + alerts.rearm(id, tick.price()) + "}");

                } else if (path.equals("/api/watch")
                        && method.equals("POST")) {

                    Map<String, String> b = body(x);
                    SymbolRef ref = registry.parse(required(b, "symbol"));

                    PriceTick tick = registry.fetch(ref);

                    alerts.watch(ref.key());
                    monitor.seed(tick);

                    sendJson(x, 200, "{\"ok\":true}");

                } else if (path.equals("/api/watch")
                        && method.equals("DELETE")) {

                    String symbol = query(x).getOrDefault("symbol", "");

                    sendJson(x, 200,
                            "{\"ok\":" + alerts.unwatch(symbol) + "}");

                } else if (path.equals("/api/interval")
                        && method.equals("POST")) {

                    int seconds = Integer.parseInt(
                            required(body(x), "seconds"));

                    if (seconds < 2 || seconds > 3600) {
                        throw new IllegalArgumentException(
                                "Interval must be between 2 and 3600 seconds.");
                    }

                    monitor.setPollSeconds(seconds);

                    sendJson(x, 200,
                            "{\"ok\":true,\"seconds\":" + seconds + "}");

                } else if (path.equals("/api/price")
                        && method.equals("GET")) {

                    String symbol = query(x).getOrDefault("symbol", "");

                    PriceTick tick = registry.fetch(
                            registry.parse(symbol));

                    monitor.seed(tick);
                    sendJson(x, 200, tickJson(tick));

                } else {
                    sendJson(x, 404,
                            "{\"error\":\"Endpoint not found\"}");
                }
                return;
            }

            send(x, 404, "text/plain; charset=utf-8", "Not found");

        } catch (IllegalArgumentException | ProviderException e) {
            sendJson(x, 400,
                    "{\"error\":" + q(
                            e.getMessage() == null
                                    ? "Invalid request" : e.getMessage()) + "}");

        } catch (Exception e) {
            e.printStackTrace();
            sendJson(x, 500,
                    "{\"error\":\"Server error. Check the terminal for details.\"}");
        }
    }

    private String state() {
        StringBuilder s = new StringBuilder(
                "{\"brand\":\"ProTrader\",\"pollSeconds\":")
                .append(monitor.pollSeconds())
                .append(",\"lastUpdate\":")
                .append(q(monitor.lastUpdate() == null
                        ? "" : monitor.lastUpdate().toString()))
                .append(",\"prices\":[");

        boolean first = true;

        for (PriceTick t : monitor.allLatest().values()) {
            if (!first) s.append(',');
            first = false;
            s.append(tickJson(t));
        }

        s.append("],\"watchlist\":[");

        first = true;

        for (String symbol : alerts.watchlist()) {
            if (!first) s.append(',');
            first = false;
            s.append(q(symbol));
        }

        s.append("],\"alerts\":[");

        first = true;

        for (Alert a : alerts.list()) {
            if (!first) s.append(',');
            first = false;

            s.append("{\"id\":").append(a.id())
                    .append(",\"symbol\":").append(q(a.symbol()))
                    .append(",\"type\":").append(q(a.type().name()))
                    .append(",\"value\":").append(a.value())
                    .append(",\"active\":").append(a.active())
                    .append(",\"created\":").append(q(a.created().toString()))
                    .append(",\"triggeredAt\":").append(q(
                            a.triggeredAt() == null
                                    ? "" : a.triggeredAt().toString()))
                    .append(",\"triggeredPrice\":")
                    .append(Double.isFinite(a.triggeredPrice())
                            ? a.triggeredPrice() : "null")
                    .append(",\"description\":").append(q(a.describe()))
                    .append('}');
        }

        return s.append("]}").toString();
    }

    private static String tickJson(PriceTick t) {
        return "{\"symbol\":" + q(t.symbol())
                + ",\"price\":" + t.price()
                + ",\"source\":" + q(t.source())
                + ",\"time\":" + q(t.time().toString()) + "}";
    }

    private static Map<String, String> body(HttpExchange x)
            throws IOException {

        String raw = new String(
                x.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);

        Map<String, String> out = new HashMap<>();

        Matcher m = Pattern.compile(
                "\"([^\"]+)\"\\s*:\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^,}\\s]+))")
                .matcher(raw);

        while (m.find()) {
            String value = m.group(2) != null
                    ? m.group(2).replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                    : m.group(3);

            out.put(m.group(1), value);
        }

        return out;
    }

    private static Map<String, String> query(HttpExchange x) {
        Map<String, String> out = new HashMap<>();
        String raw = x.getRequestURI().getRawQuery();

        if (raw == null) return out;

        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);

            out.put(
                    URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1
                            ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                            : "");
        }

        return out;
    }

    private static String required(Map<String, String> b, String key) {
        String v = b.get(key);

        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing field: " + key);
        }

        return v.trim();
    }

    private static double positive(String s) {
        try {
            double v = Double.parseDouble(s);

            if (!Double.isFinite(v) || v <= 0) {
                throw new NumberFormatException();
            }

            return v;

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Value must be a positive number.");
        }
    }

    private static String q(String value) {
        if (value == null) return "null";

        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private static void sendJson(
            HttpExchange x, int status, String value) throws IOException {

        send(x, status, "application/json; charset=utf-8", value);
    }

    private static void send(
            HttpExchange x, int status, String type, String value)
            throws IOException {

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");

        x.sendResponseHeaders(status, bytes.length);

        try (var out = x.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final String PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ProTrader — Market Intelligence</title>

<style>
:root {
 --bg:#090d16;
 --panel:#111827;
 --line:#243148;
 --muted:#8b9bb4;
 --text:#eef4ff;
 --green:#24d6a0;
 --red:#ff647c;
 --blue:#719bff;
 --gold:#f4c76b;
}
* { box-sizing:border-box; }
body {
 margin:0;
 background:radial-gradient(ellipse at 70% -20%,#1b3152 0,transparent 45%),var(--bg);
 color:var(--text);
 font:14px Inter,Segoe UI,Arial,sans-serif;
}
button,input,select { font:inherit; }
button {
 cursor:pointer;border:0;border-radius:9px;
 padding:10px 14px;background:var(--blue);
 color:#07101f;font-weight:700;
}
button.secondary {
 background:#202c40;color:#dce7fa;
 border:1px solid var(--line);
}
button.danger {
 background:#442334;color:#ffb4c0;
 border:1px solid #673348;
}
.app { display:grid;grid-template-columns:230px 1fr;min-height:100vh; }
.side {
 background:#0c1320;border-right:1px solid var(--line);
 padding:25px 16px;display:flex;flex-direction:column;gap:28px;
}
.brand { display:flex;align-items:center;gap:11px;padding:0 8px; }
.logo {
 width:36px;height:36px;border-radius:11px;
 background:linear-gradient(135deg,#69a0ff,#36e0b0);
 display:grid;place-items:center;color:#081321;font-weight:900;font-size:19px;
}
.brand b { font-size:19px;letter-spacing:.4px; }
.brand small {
 display:block;color:var(--muted);font-size:10px;
 letter-spacing:1.5px;margin-top:3px;
}
.nav { display:grid;gap:7px; }
.nav button { background:transparent;color:#9eacc2;text-align:left;font-weight:600; }
.nav button.active,.nav button:hover { background:#1a2940;color:white; }
.nav button span { display:inline-block;width:25px; }
.sidefoot { margin-top:auto;color:#72829c;font-size:11px;line-height:1.7; }
.main { padding:27px 32px;max-width:1600px;width:100%;margin:auto; }
.top {
 display:flex;justify-content:space-between;align-items:center;
 gap:15px;margin-bottom:25px;
}
.eyebrow { color:var(--green);font-size:10px;font-weight:800;letter-spacing:2px; }
.top h1 { font-size:25px;margin:5px 0; }
.sub { color:var(--muted); }
.status {
 display:flex;align-items:center;gap:8px;color:#a8b8cf;
 background:#111c2b;border:1px solid var(--line);
 padding:9px 13px;border-radius:20px;font-size:12px;
}
.dot { width:7px;height:7px;border-radius:50%;background:var(--green); }
.cards { display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:18px; }
.card,.panel {
 background:linear-gradient(145deg,rgba(21,32,49,.97),rgba(14,22,35,.97));
 border:1px solid var(--line);border-radius:15px;
}
.card { padding:18px; }
.card label { color:var(--muted);font-size:12px; }
.metric { font-size:25px;font-weight:750;margin:10px 0 5px; }
.hint { color:#8192aa;font-size:11px; }
.green { color:var(--green); }
.red { color:var(--red); }
.layout {
 display:grid;grid-template-columns:minmax(0,1.65fr) minmax(280px,1fr);
 gap:16px;
}
.panel { padding:19px;margin-bottom:16px; }
.panelhead {
 display:flex;justify-content:space-between;
 align-items:center;gap:12px;margin-bottom:15px;
}
.panel h2 { font-size:15px;margin:0; }
.panel p { color:var(--muted);margin:5px 0 0;font-size:12px; }
.marketrow {
 display:grid;grid-template-columns:1.4fr 1fr 1fr .7fr;
 align-items:center;gap:10px;padding:13px 0;
 border-top:1px solid #202c40;
}
.asset { display:flex;align-items:center;gap:10px;font-weight:700; }
.coin {
 width:30px;height:30px;border-radius:50%;background:#253858;
 display:grid;place-items:center;color:#b8ceff;font-size:11px;
}
.symbol { font-size:11px;color:var(--muted);margin-top:3px; }
.price { font-weight:700; }
.chart { height:190px;width:100%;display:block; }
.chartnote {
 display:flex;justify-content:space-between;
 color:var(--muted);font-size:11px;margin-top:6px;
}
.formgrid { display:grid;grid-template-columns:1fr 1fr;gap:10px; }
.field { display:grid;gap:6px; }
.field.full { grid-column:1/-1; }
.field label { color:#a7b5ca;font-size:11px; }
.field input,.field select {
 width:100%;padding:11px;background:#0c1523;
 border:1px solid #2a3a54;border-radius:8px;
 color:#eef4ff;outline:none;
}
.field input:focus,.field select:focus { border-color:var(--blue); }
.formactions { display:flex;gap:8px;margin-top:12px; }
.tablewrap { overflow:auto; }
table { width:100%;border-collapse:collapse;white-space:nowrap; }
th {
 text-align:left;color:#8292ab;font-size:10px;
 letter-spacing:1px;font-weight:700;padding:10px;
 border-bottom:1px solid var(--line);
}
td { padding:12px 10px;border-bottom:1px solid #202c40;font-size:12px; }
.pill {
 display:inline-block;padding:4px 8px;border-radius:20px;
 font-size:10px;background:#15382f;color:var(--green);
}
.pill.off { background:#352b22;color:var(--gold); }
.empty { text-align:center;color:var(--muted);padding:25px; }
.view { display:none; }
.view.active { display:block; }
.toast {
 position:fixed;right:22px;bottom:22px;
 background:#1a2b42;border:1px solid #3a5275;
 border-radius:10px;padding:13px 17px;display:none;max-width:360px;
}
.smallbtn { padding:6px 9px;font-size:11px; }
.muted { color:var(--muted); }
.watchitem {
 display:flex;align-items:center;justify-content:space-between;
 padding:11px 0;border-top:1px solid #202c40;
}
.footer { color:#61718b;font-size:10px;text-align:center;padding:20px; }
@media(max-width:950px) {
 .app { grid-template-columns:1fr; }
 .side { padding:13px;gap:12px; }
 .nav { grid-template-columns:repeat(4,1fr); }
 .nav button { font-size:11px;padding:9px; }
 .sidefoot { display:none; }
 .main { padding:20px; }
 .cards { grid-template-columns:repeat(2,1fr); }
}
@media(max-width:650px) {
 .layout { grid-template-columns:1fr; }
 .cards { gap:8px; }
 .card { padding:13px; }
 .metric { font-size:20px; }
 .main { padding:14px; }
 .top { align-items:flex-start;flex-direction:column; }
 .nav { grid-template-columns:repeat(2,1fr); }
 .marketrow { grid-template-columns:1.3fr 1fr .8fr; }
 .marketrow>:nth-child(3) { display:none; }
}
</style>
</head>

<body>
<div class="app">

<aside class="side">
 <div class="brand">
  <div class="logo">P</div>
  <div><b>ProTrader</b><small>MARKET INTELLIGENCE</small></div>
 </div>

 <nav class="nav">
  <button class="active" data-view="overview"><span>▦</span>Overview</button>
  <button data-view="market"><span>◈</span>Live Market</button>
  <button data-view="alerts"><span>◎</span>Price Alerts</button>
  <button data-view="watchlist"><span>☆</span>Watchlist</button>
  <button data-view="settings"><span>⚙</span>Settings</button>
 </nav>

 <div class="sidefoot">
  PROTRADER TERMINAL<br>
  Local Java-powered dashboard<br>
  <span id="serverState">Connecting…</span>
 </div>
</aside>

<main class="main">

<header class="top">
 <div>
  <div class="eyebrow">MARKET OVERVIEW</div>
  <h1 id="pageTitle">Your trading command center</h1>
  <div class="sub">Track markets. Set your levels. Stay ahead.</div>
 </div>
 <div class="status">
  <i class="dot"></i>
  <span id="lastUpdate">Connecting to market feed…</span>
 </div>
</header>

<section class="view active" id="overview">

 <div class="cards">
  <div class="card"><label>Tracked assets</label><div class="metric" id="assetCount">0</div><div class="hint">Symbols in your watchlist</div></div>
  <div class="card"><label>Active alerts</label><div class="metric" id="activeCount">0</div><div class="hint">Rules currently monitoring</div></div>
  <div class="card"><label>Triggered alerts</label><div class="metric" id="triggeredCount">0</div><div class="hint">Alerts that have fired</div></div>
  <div class="card"><label>Refresh interval</label><div class="metric" id="pollMetric">10s</div><div class="hint">Background price polling</div></div>
 </div>

 <div class="layout">
  <div>
   <div class="panel">
    <div class="panelhead">
     <div><h2>Market watch</h2><p>Latest prices from your configured providers</p></div>
     <button class="secondary smallbtn" onclick="refresh()">↻ Refresh</button>
    </div>
    <div id="marketRows"><div class="empty">Waiting for your first tracked asset…</div></div>
   </div>

   <div class="panel">
    <div class="panelhead">
     <div><h2>Price movement</h2><p>Recent snapshots collected during this session</p></div>
    </div>
    <div class="chartwrap"><canvas class="chart" id="chart"></canvas></div>
    <div class="chartnote"><span>Session history</span><span id="chartSymbol">Select a tracked asset</span></div>
   </div>
  </div>

  <div>
   <div class="panel">
    <div class="panelhead">
     <div><h2>Create price alert</h2><p>Start monitoring a target in seconds</p></div>
    </div>
    <form id="alertForm">
     <div class="formgrid">
      <div class="field full">
       <label>SYMBOL / TICKER</label>
       <input name="symbol" placeholder="BTCUSDT, RELIANCE.NS, coingecko:bitcoin" required>
      </div>
      <div class="field">
       <label>ALERT RULE</label>
       <select name="type">
        <option value="ABOVE">Price rises above</option>
        <option value="BELOW">Price falls below</option>
        <option value="PERCENT_MOVE">Moves by ± %</option>
        <option value="TRAILING_STOP">Trailing stop %</option>
       </select>
      </div>
      <div class="field">
       <label>TARGET / PERCENT</label>
       <input name="value" type="number" min="0.000001" step="any" placeholder="95000" required>
      </div>
     </div>
     <div class="formactions"><button type="submit">＋ Create alert</button></div>
    </form>
   </div>

   <div class="panel">
    <div class="panelhead">
     <div><h2>Recent alerts</h2><p>Your latest alert activity</p></div>
     <button class="secondary smallbtn" onclick="showView('alerts')">View all</button>
    </div>
    <div id="recentAlerts" class="empty">No alerts created yet.</div>
   </div>
  </div>
 </div>
</section>

<section class="view" id="market">
 <div class="panel">
  <div class="panelhead">
   <div><h2>Live market</h2><p>All symbols currently receiving prices</p></div>
   <button class="secondary" onclick="refresh()">↻ Refresh</button>
  </div>
  <div id="allMarketRows"></div>
 </div>
</section>

<section class="view" id="alerts">
 <div class="panel">
  <div class="panelhead">
   <div><h2>Alert management</h2><p>Re-arm triggered rules or remove alerts</p></div>
  </div>
  <div class="tablewrap">
   <table>
    <thead><tr><th>ID</th><th>SYMBOL</th><th>RULE</th><th>STATUS</th><th>TRIGGERED PRICE</th><th>ACTION</th></tr></thead>
    <tbody id="alertTable"></tbody>
   </table>
  </div>
 </div>
</section>

<section class="view" id="watchlist">
 <div class="layout">
  <div class="panel">
   <div class="panelhead"><div><h2>Your watchlist</h2><p>Symbols monitored in the background</p></div></div>
   <div id="watchItems"></div>
  </div>
  <div class="panel">
   <h2>Add to watchlist</h2>
   <p>Use provider:ticker or a supported ticker</p>
   <form id="watchForm" style="margin-top:15px">
    <div class="field">
     <label>SYMBOL</label>
     <input name="symbol" placeholder="BTCUSDT or RELIANCE.NS" required>
    </div>
    <div class="formactions"><button>Add symbol</button></div>
   </form>
  </div>
 </div>
</section>

<section class="view" id="settings">
 <div class="panel" style="max-width:600px">
  <h2>Dashboard settings</h2>
  <p>Control how often ProTrader checks your tracked assets.</p>
  <form id="settingsForm" style="margin-top:18px">
   <div class="field">
    <label>POLL INTERVAL (2–3600 SECONDS)</label>
    <input type="number" name="seconds" min="2" max="3600" value="10" required>
   </div>
   <div class="formactions"><button>Save settings</button></div>
  </form>
  <p style="margin-top:20px">Data providers: Binance · Yahoo Finance · CoinGecko · Finnhub · Simulator</p>
  <p>Telegram alerts are configured through <b>config.properties</b> in the project folder.</p>
 </div>
</section>

<div class="footer">
 ProTrader · Java-powered market monitoring · For education and research, not financial advice.
</div>

</main>
</div>

<div class="toast" id="toast"></div>

<script>
let state = {prices:[],alerts:[],watchlist:[],pollSeconds:10};
let selectedSymbol = null;

const $ = id => document.getElementById(id);

function toast(s) {
 $('toast').textContent = s;
 $('toast').style.display = 'block';
 setTimeout(() => $('toast').style.display = 'none',3500);
}

function esc(s) {
 return String(s ?? '').replace(/[&<>"']/g,c => ({
  '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
 }[c]));
}

function money(n) {
 return Number(n).toLocaleString(undefined,{
  minimumFractionDigits:2,maximumFractionDigits:6
 });
}

function showView(v) {
 document.querySelectorAll('.view').forEach(e =>
  e.classList.toggle('active',e.id===v));

 document.querySelectorAll('.nav button').forEach(e =>
  e.classList.toggle('active',e.dataset.view===v));

 $('pageTitle').textContent = ({
  overview:'Your trading command center',
  market:'Live market prices',
  alerts:'Price alert management',
  watchlist:'Your watchlist',
  settings:'Dashboard settings'
 })[v];
}

document.querySelectorAll('.nav button').forEach(b =>
 b.onclick = () => showView(b.dataset.view));

async function api(url,opts={}) {
 let r = await fetch(url,{
  headers:{'Content-Type':'application/json'},
  ...opts
 });
 let j = await r.json();
 if(!r.ok) throw Error(j.error || 'Request failed');
 return j;
}

async function load() {
 try {
  state = await api('/api/state');
  render();

  $('serverState').textContent = 'Server connected';

  $('lastUpdate').textContent = state.lastUpdate
   ? 'Updated ' + new Date(state.lastUpdate).toLocaleTimeString()
   : 'Waiting for prices';

 } catch(e) {
  $('serverState').textContent = 'Connection issue';
 }
}

function render() {
 let prices = state.prices || [];
 let alerts = state.alerts || [];
 let watch = state.watchlist || [];

 $('assetCount').textContent = watch.length;
 $('activeCount').textContent = alerts.filter(a=>a.active).length;
 $('triggeredCount').textContent = alerts.filter(a=>!a.active).length;
 $('pollMetric').textContent = state.pollSeconds+'s';
 $('settingsForm').seconds.value = state.pollSeconds;

 let rows = prices.map(p => `
  <div class="marketrow">
   <div class="asset">
    <div class="coin">${esc(p.symbol.split(':').pop().slice(0,3))}</div>
    <div>${esc(p.symbol)}<div class="symbol">${esc(p.source)}</div></div>
   </div>
   <div class="price">${money(p.price)}</div>
   <div class="muted">${new Date(p.time).toLocaleTimeString()}</div>
   <button class="secondary smallbtn" onclick="selectChart('${esc(p.symbol)}')">Chart</button>
  </div>
 `).join('') || '<div class="empty">Add an asset to your watchlist to start receiving prices.</div>';

 $('marketRows').innerHTML = rows;
 $('allMarketRows').innerHTML = rows;

 $('recentAlerts').innerHTML = alerts.length
  ? alerts.slice(-5).reverse().map(a => `
   <div class="watchitem">
    <div><b>${esc(a.symbol)}</b><div class="symbol">${esc(a.description)}</div></div>
    <span class="pill ${a.active?'':'off'}">${a.active?'ACTIVE':'TRIGGERED'}</span>
   </div>
  `).join('')
  : 'No alerts created yet.';

 $('alertTable').innerHTML = alerts.length
  ? alerts.slice().reverse().map(a => `
   <tr>
    <td>#${a.id}</td>
    <td><b>${esc(a.symbol)}</b></td>
    <td>${esc(a.description)}</td>
    <td><span class="pill ${a.active?'':'off'}">${a.active?'ACTIVE':'TRIGGERED'}</span></td>
    <td>${a.triggeredPrice==null?'—':money(a.triggeredPrice)}</td>
    <td>
     ${a.active?'':'<button class="secondary smallbtn" onclick="rearm('+a.id+')">Re-arm</button> '}
     <button class="danger smallbtn" onclick="removeAlert(${a.id})">Delete</button>
    </td>
   </tr>
  `).join('')
  : '<tr><td colspan="6" class="empty">No alerts yet.</td></tr>';

 $('watchItems').innerHTML = watch.length
  ? watch.map(s => `
   <div class="watchitem">
    <div>
     <b>${esc(s)}</b>
     <div class="symbol">Tracked market symbol</div>
    </div>
    <button class="danger smallbtn" onclick="unwatch('${esc(s)}')">Remove</button>
   </div>
  `).join('')
  : '<div class="empty">Your watchlist is empty.</div>';

 if(!selectedSymbol && prices.length) selectChart(prices[0].symbol);
 else if(selectedSymbol) drawChart();
}

async function refresh() {
 await load();
 toast('Dashboard refreshed');
}

function selectChart(s) {
 selectedSymbol = s;
 $('chartSymbol').textContent = s;
 drawChart();
}

function drawChart() {
 let c = $('chart');
 let ctx = c.getContext('2d');
 let dpr = window.devicePixelRatio || 1;
 let w = c.clientWidth;
 let h = c.clientHeight;

 c.width = w*dpr;
 c.height = h*dpr;
 ctx.scale(dpr,dpr);
 ctx.clearRect(0,0,w,h);

 let p = state.prices.find(x=>x.symbol===selectedSymbol);

 if(!p) {
  ctx.fillStyle = '#8494ad';
  ctx.fillText('Waiting for market prices.',12,30);
  return;
 }

 for(let i=1;i<4;i++) {
  let y = h*i/4;
  ctx.strokeStyle = '#283850';
  ctx.beginPath();
  ctx.moveTo(0,y);
  ctx.lineTo(w,y);
  ctx.stroke();
 }

 ctx.strokeStyle = '#39d6ac';
 ctx.lineWidth = 2.5;
 ctx.beginPath();
 ctx.moveTo(8,h/2);
 ctx.lineTo(w-8,h/2);
 ctx.stroke();

 ctx.fillStyle = '#39d6ac';
 ctx.font = '12px Segoe UI';
 ctx.fillText(money(p.price),12,20);
}

$('alertForm').onsubmit = async e => {
 e.preventDefault();
 let f = new FormData(e.target);

 try {
  await api('/api/alerts',{
   method:'POST',
   body:JSON.stringify(Object.fromEntries(f))
  });

  e.target.reset();
  await load();
  toast('Price alert created');

 } catch(err) {
  toast(err.message);
 }
};

$('watchForm').onsubmit = async e => {
 e.preventDefault();
 let symbol = new FormData(e.target).get('symbol');

 try {
  await api('/api/watch',{
   method:'POST',
   body:JSON.stringify({symbol})
  });

  e.target.reset();
  await load();
  toast('Symbol added to watchlist');

 } catch(err) {
  toast(err.message);
 }
};

async function unwatch(s) {
 try {
  await api('/api/watch?symbol='+encodeURIComponent(s),{
   method:'DELETE'
  });
  await load();
  toast('Removed from watchlist');
 } catch(e) { toast(e.message); }
}

async function removeAlert(id) {
 try {
  await api('/api/alerts?id='+id,{method:'DELETE'});
  await load();
  toast('Alert deleted');
 } catch(e) { toast(e.message); }
}

async function rearm(id) {
 try {
  await api('/api/rearm',{
   method:'POST',
   body:JSON.stringify({id})
  });
  await load();
  toast('Alert re-armed');
 } catch(e) { toast(e.message); }
}

$('settingsForm').onsubmit = async e => {
 e.preventDefault();

 try {
  let seconds = Number(new FormData(e.target).get('seconds'));

  await api('/api/interval',{
   method:'POST',
   body:JSON.stringify({seconds})
  });

  await load();
  toast('Refresh interval saved');

 } catch(err) {
  toast(err.message);
 }
};

load();
setInterval(load,5000);
window.addEventListener('resize',drawChart);
</script>
</body>
</html>
""";
}
