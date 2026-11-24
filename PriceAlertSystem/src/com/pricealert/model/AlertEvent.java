package com.pricealert.model;

import java.time.Instant;

/**
 * Describes "alert number N just fired". This is what notifiers (console, file, Telegram) receive.
 *
 * @param condition plain-English reason, e.g. "price rises to 95,000.00 or above"
 */
public record AlertEvent(int alertId, String symbol, double price, String condition, Instant time) { }
