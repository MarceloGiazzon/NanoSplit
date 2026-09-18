package com.nanosplit.config;

/** Raised for a missing, malformed, or unusable configuration value. */
public class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
