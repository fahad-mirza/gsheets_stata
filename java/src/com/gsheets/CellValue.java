// =============================================================================
// CellValue.java - gsheets package
// Represents a single cell value as returned by the Sheets API values.get.
// Holds the raw string form and a resolved Java type for Stata type inference.
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

public class CellValue {

    // ------------------------------------------------------------------
    // Cell type enum
    // ------------------------------------------------------------------
    public enum Type {
        NUMERIC,   // can be stored as Stata double
        STRING,    // must be stored as Stata str#
        EMPTY      // missing -- stored as . or "" depending on column type
    }

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------
    public final String raw;    // original string from JSON (never null)
    public final Type   type;
    public final double numeric; // only valid when type == NUMERIC

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    private CellValue(String raw, Type type, double numeric) {
        this.raw     = raw;
        this.type    = type;
        this.numeric = numeric;
    }

    // ------------------------------------------------------------------
    // Factory methods
    // ------------------------------------------------------------------

    /** Empty cell -- returned when the API omits trailing empty cells. */
    public static CellValue empty() {
        return new CellValue("", Type.EMPTY, Double.NaN);
    }

    /**
     * Parse a raw value from the JSON array.
     * The Sheets API returns numbers as JSON numbers (no quotes) and
     * strings as JSON strings. Gson converts both to String when we
     * read the values array as strings, so we attempt numeric parsing.
     */
    public static CellValue of(String raw) {
        if (raw == null || raw.isEmpty()) {
            return empty();
        }

        // Attempt numeric parse
        try {
            double d = Double.parseDouble(raw);
            // Reject Infinity and NaN as numeric -- treat as string
            if (Double.isFinite(d)) {
                return new CellValue(raw, Type.NUMERIC, d);
            }
        } catch (NumberFormatException ignored) { }

        // String cell
        return new CellValue(raw, Type.STRING, Double.NaN);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public boolean isEmpty()   { return type == Type.EMPTY;   }
    public boolean isNumeric() { return type == Type.NUMERIC; }
    public boolean isString()  { return type == Type.STRING;  }

    @Override
    public String toString() {
        return "CellValue{type=" + type + ", raw='" + raw + "'}";
    }
}
