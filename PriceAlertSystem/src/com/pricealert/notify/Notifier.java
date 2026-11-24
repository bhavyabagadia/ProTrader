package com.pricealert.notify;

import com.pricealert.model.AlertEvent;

/**
 * Anything that can tell the user "an alert fired" (Observer pattern).
 * To add email or WhatsApp later, write a new class implementing this and register it - nothing else changes.
 */
public interface Notifier {
    void notify(AlertEvent event);
}
