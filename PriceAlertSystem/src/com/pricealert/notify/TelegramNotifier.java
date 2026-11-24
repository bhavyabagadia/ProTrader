package com.pricealert.notify;

import com.pricealert.model.AlertEvent;
import com.pricealert.provider.Http;
import com.pricealert.provider.ProviderException;
import com.pricealert.util.Fmt;

import java.util.Map;

/**
 * OPTIONAL: pushes the alert to your phone through a free Telegram bot.
 * Setup: talk to @BotFather in Telegram -> /newbot -> copy the token; send any message to your bot,
 * then open https://api.telegram.org/bot<TOKEN>/getUpdates to find your chat id.
 */
public class TelegramNotifier implements Notifier {
    private final String baseUrl;
    private final String token;
    private final String chatId;

    public TelegramNotifier(String baseUrl, String token, String chatId) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.chatId = chatId;
    }

    public static final String DEFAULT_BASE = "https://api.telegram.org";

    @Override
    public void notify(AlertEvent e) {
        String text = "ALERT #" + e.alertId() + " " + e.symbol() + "\n"
                + "Price: " + Fmt.price(e.price()) + "\n" + e.condition();
        try {
            Http.postForm(baseUrl + "/bot" + token + "/sendMessage", Map.of("chat_id", chatId, "text", text));
        } catch (ProviderException ex) {
            // A phone-message failure must never break the console alert, so only report it.
            System.err.println("Telegram message failed: " + ex.getMessage());
        }
    }
}
