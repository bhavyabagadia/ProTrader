package com.pricealert.notify;

import com.pricealert.model.AlertEvent;
import com.pricealert.util.Fmt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends every fired alert to a log file (a permanent history you can show your teacher). */
public class FileNotifier implements Notifier {
    private final Path file;

    public FileNotifier(Path file) { this.file = file; }

    @Override
    public synchronized void notify(AlertEvent e) {
        String line = Fmt.stamp(e.time()) + " | alert #" + e.alertId() + " | " + e.symbol()
                + " | price " + Fmt.price(e.price()) + " | " + e.condition() + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.println("Could not write " + file + ": " + ex.getMessage());
        }
    }
}
