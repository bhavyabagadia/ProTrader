package com.pricealert.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** One place for all web requests, using the HttpClient built into Java 11+. */
public final class Http {
    private Http() { }

    // One shared client is cheaper than creating a new one for every request.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static String get(String url, Map<String, String> headers) throws ProviderException {
        HttpRequest.Builder b = builder(url);
        headers.forEach(b::setHeader);
        return send(b.GET().build());
    }

    public static String postForm(String url, Map<String, String> form) throws ProviderException {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest req = builder(url)
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(req);
    }

    public static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static HttpRequest.Builder builder(String url) throws ProviderException {
        try {
            return HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .setHeader("User-Agent", "PriceAlertSystem/1.0 (college project)")
                    .setHeader("Accept", "application/json");
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Bad URL: " + e.getMessage(), e);
        }
    }

    private static String send(HttpRequest req) throws ProviderException {
        try {
            HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            if (code == 429) {
                throw new ProviderException("Rate limit hit (HTTP 429). Use a longer poll interval or wait a minute.");
            }
            if (code == 451) {
                throw new ProviderException("This service is blocked in your region (HTTP 451). "
                        + "Try another provider, e.g. coingecko:bitcoin or yahoo:BTC-USD, or sim: for offline mode.");
            }
            if (code == 401 || code == 403) {
                throw new ProviderException("Access denied (HTTP " + code + "). Check your API key.");
            }
            if (code / 100 != 2) {
                throw new ProviderException("HTTP " + code + " from " + req.uri().getHost() + ": " + snippet(r.body()));
            }
            return r.body();
        } catch (IOException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ProviderException("Network problem: " + why, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the "I was interrupted" flag for whoever called us
            throw new ProviderException("Interrupted while fetching", e);
        }
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String t = body.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) + "..." : t;
    }
}
