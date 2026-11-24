package com.pricealert.provider;

/** Thrown when a price cannot be fetched (no internet, bad symbol, rate limit, bad key...). */
public class ProviderException extends Exception {
    private static final long serialVersionUID = 1L;

    public ProviderException(String message) { super(message); }

    public ProviderException(String message, Throwable cause) { super(message, cause); }
}
