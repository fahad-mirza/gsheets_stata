// =============================================================================
// GsheetsException.java - gsheets package
// Single checked exception used throughout the Java layer.
// Messages are surfaced directly to the Stata user via the .ado error handler.
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

public class GsheetsException extends Exception {

    public GsheetsException(String message) {
        super(message);
    }

    public GsheetsException(String message, Throwable cause) {
        super(message, cause);
    }
}
