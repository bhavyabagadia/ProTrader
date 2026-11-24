package com.pricealert.model;

/** The kinds of alert the user can create. */
public enum AlertType {
    ABOVE("Price rises to a target"),
    BELOW("Price falls to a target"),
    PERCENT_MOVE("Price moves X% away from the starting price (up or down)"),
    TRAILING_STOP("Trailing stop-loss: price falls X% from its highest point");

    private final String label;

    AlertType(String label) { this.label = label; }

    public String label() { return label; }
}
