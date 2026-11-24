package com.pricealert.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads optional settings (API keys etc.) from config.properties.
 * An environment variable, if set, wins over the file. Missing values simply give "".
 */
public final class AppConfig {
    private final Properties props = new Properties();

    public AppConfig(Path file) {
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Could not read " + file + ": " + e.getMessage());
            }
        }
    }

    public static AppConfig empty() { return new AppConfig(null); }

    /** Priority: environment variable, then config.properties, then the default. */
    public String get(String key, String envName, String def) {
        String env = envName == null ? null : System.getenv(envName);
        if (env != null && !env.isBlank()) return env.trim();
        String v = props.getProperty(key);
        // values like YOUR_FINNHUB_KEY (copied from the example file) count as "not set"
        if (v != null && !v.isBlank() && !v.trim().startsWith("YOUR_")) return v.trim();
        return def;
    }

    public String finnhubKey() { return get("finnhub.key", "FINNHUB_API_KEY", ""); }

    public String coingeckoKey() { return get("coingecko.key", "COINGECKO_API_KEY", ""); }

    public String coingeckoCurrency() { return get("coingecko.currency", null, "usd").toLowerCase(); }

    public String telegramToken() { return get("telegram.token", "TELEGRAM_BOT_TOKEN", ""); }

    public String telegramChat() { return get("telegram.chat", "TELEGRAM_CHAT_ID", ""); }

    public int pollSeconds() {
        try {
            return Math.max(1, Integer.parseInt(get("poll.seconds", null, "10")));
        } catch (NumberFormatException e) {
            return 10;
        }
    }
}
