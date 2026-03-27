// =============================================================================
// SpreadsheetMeta.java - gsheets package
// Holds spreadsheet metadata returned by the Sheets API spreadsheets.get call.
// Parsed from the JSON response and passed back to the Stata layer.
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import java.util.List;
import java.util.ArrayList;

public class SpreadsheetMeta {

    // Spreadsheet-level properties
    public String spreadsheetId;
    public String title;

    // One entry per sheet/tab
    public List<SheetMeta> sheets = new ArrayList<>();

    // ------------------------------------------------------------------
    // Inner class: metadata for one sheet/tab
    // ------------------------------------------------------------------
    public static class SheetMeta {
        public int    sheetId;
        public String title;
        public int    index;
        public String sheetType;    // GRID, OBJECT, etc.
        public int    rowCount;
        public int    columnCount;
        public boolean hidden;
    }

    // ------------------------------------------------------------------
    // Convenience accessors for the Stata layer
    // ------------------------------------------------------------------

    /** Number of sheets in the spreadsheet. */
    public int getSheetCount() {
        return sheets.size();
    }

    /**
     * Pipe-delimited list of sheet names, in tab order.
     * Stata will split this on | to get individual names.
     * Uses | rather than space because sheet names can contain spaces.
     */
    public String getSheetNamesPipeDelimited() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sheets.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(sheets.get(i).title);
        }
        return sb.toString();
    }

    /**
     * Returns the SheetMeta for the first visible grid sheet,
     * or the first sheet if all are non-grid/hidden.
     */
    public SheetMeta getDefaultSheet() {
        for (SheetMeta s : sheets) {
            if (!s.hidden && "GRID".equals(s.sheetType)) return s;
        }
        return sheets.isEmpty() ? null : sheets.get(0);
    }

    /**
     * Find a sheet by name (case-insensitive).
     * Returns null if not found.
     */
    public SheetMeta findSheetByName(String name) {
        for (SheetMeta s : sheets) {
            if (s.title.equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    /**
     * Find a sheet by 1-based index.
     * Returns null if out of range.
     */
    public SheetMeta findSheetByIndex(int oneBasedIndex) {
        int idx = oneBasedIndex - 1;
        if (idx < 0 || idx >= sheets.size()) return null;
        return sheets.get(idx);
    }
}
