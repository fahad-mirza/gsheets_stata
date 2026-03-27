// =============================================================================
// StataWriter.java - gsheets package
// Writes a 2D list of CellValues directly into the Stata dataset via the
// SFI Data class. Performs column type inference and variable naming.
// This class is always called single-threaded -- SFI is not thread-safe.
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.stata.sfi.Data;
import com.stata.sfi.SFIToolkit;

import java.util.ArrayList;
import java.util.List;

public class StataWriter {

    // Maximum str width Stata supports
    private static final int MAX_STR_WIDTH = 2045;

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Writes rows of CellValues into the current Stata dataset.
     * Clears any existing data first.
     *
     * @param rows       all rows including header row if firstRow=true
     * @param firstRow   if true, row 0 is variable names not data
     * @param verbose    print variable summary to Stata output
     */
    public static void write(List<List<CellValue>> rows,
                              boolean firstRow,
                              boolean verbose)
            throws GsheetsException {

        if (rows == null || rows.isEmpty()) {
            throw new GsheetsException(
                "The sheet contains no data.");
        }

        // Split header from data rows
        List<String>         varNames;
        List<List<CellValue>> dataRows;

        if (firstRow) {
            varNames = extractVarNames(rows.get(0));
            dataRows = rows.subList(1, rows.size());
        } else {
            varNames = null;
            dataRows = rows;
        }

        if (dataRows.isEmpty()) {
            throw new GsheetsException(
                "The sheet has a header row but no data rows.");
        }

        // Determine number of columns from widest row
        int nCols = 0;
        for (List<CellValue> row : dataRows) {
            if (row.size() > nCols) nCols = row.size();
        }
        if (nCols == 0) {
            throw new GsheetsException("Sheet appears to have no columns.");
        }

        int nRows = dataRows.size();

        // Generate default var names if no header
        if (varNames == null) {
            varNames = generateVarNames(nCols);
        } else {
            // Pad or trim to match nCols
            while (varNames.size() < nCols) {
                varNames.add("v" + (varNames.size() + 1));
            }
        }

        // Infer column types
        ColumnType[] colTypes = inferColumnTypes(dataRows, nCols);

        // Compute string widths for str columns
        int[] strWidths = computeStrWidths(dataRows, colTypes, nCols);

        if (verbose) {
            SFIToolkit.displayln("{txt}Creating " + nCols +
                " variable(s), " + nRows + " observation(s)...");
        }

        // Set observation count
        // Note: dataset is cleared by import_gsheet.ado before javacall
        int rc = Data.setObsCount(nRows);
        if (rc != 0) {
            throw new GsheetsException(
                "Could not set observation count to " + nRows +
                " (Stata error code " + rc + ").");
        }

        // Create variables
        int[] varIndices = new int[nCols];
        for (int c = 0; c < nCols; c++) {
            String safeName = toSafeVarName(varNames.get(c), c);
            if (colTypes[c] == ColumnType.NUMERIC) {
                rc = Data.addVarDouble(safeName);
            } else {
                int width = Math.min(strWidths[c], MAX_STR_WIDTH);
                rc = Data.addVarStr(safeName, width);
            }
            if (rc != 0) {
                throw new GsheetsException(
                    "Could not create variable '" + safeName +
                    "' (Stata error code " + rc + ").");
            }
            varIndices[c] = Data.getVarIndex(safeName);
        }

        // Write data row by row
        for (int r = 0; r < nRows; r++) {
            List<CellValue> row = dataRows.get(r);
            for (int c = 0; c < nCols; c++) {
                CellValue cell = c < row.size() ? row.get(c) : CellValue.empty();
                int varIdx = varIndices[c];

                if (colTypes[c] == ColumnType.NUMERIC) {
                    double val = cell.isEmpty() ? Data.getMissingValue()
                                                : cell.numeric;
                    Data.storeNum(varIdx, r + 1, val);
                } else {
                    String val = cell.isEmpty() ? "" : cell.raw;
                    Data.storeStr(varIdx, r + 1, val);
                }
            }
        }

        if (verbose) {
            SFIToolkit.displayln("{txt}Done.");
        }
    }

    // ------------------------------------------------------------------
    // Type inference
    // ------------------------------------------------------------------

    private enum ColumnType { NUMERIC, STRING }

    /**
     * For each column: if every non-empty cell is numeric, the column
     * is NUMERIC. Any single string cell makes the whole column STRING.
     */
    private static ColumnType[] inferColumnTypes(List<List<CellValue>> rows,
                                                   int nCols) {
        ColumnType[] types = new ColumnType[nCols];
        for (int c = 0; c < nCols; c++) {
            types[c] = ColumnType.NUMERIC; // optimistic default
        }

        for (List<CellValue> row : rows) {
            for (int c = 0; c < row.size() && c < nCols; c++) {
                CellValue cell = row.get(c);
                if (cell.isString()) {
                    types[c] = ColumnType.STRING;
                }
            }
        }
        return types;
    }

    // ------------------------------------------------------------------
    // String width computation
    // ------------------------------------------------------------------

    private static int[] computeStrWidths(List<List<CellValue>> rows,
                                           ColumnType[] colTypes,
                                           int nCols) {
        int[] widths = new int[nCols];
        for (int c = 0; c < nCols; c++) {
            widths[c] = 1; // minimum width 1
        }

        for (List<CellValue> row : rows) {
            for (int c = 0; c < row.size() && c < nCols; c++) {
                if (colTypes[c] == ColumnType.STRING) {
                    int len = row.get(c).raw.length();
                    if (len > widths[c]) widths[c] = len;
                }
            }
        }
        return widths;
    }

    // ------------------------------------------------------------------
    // Variable name helpers
    // ------------------------------------------------------------------

    private static List<String> extractVarNames(List<CellValue> headerRow) {
        List<String> names = new ArrayList<>();
        for (CellValue cell : headerRow) {
            names.add(cell.raw.trim());
        }
        return names;
    }

    private static List<String> generateVarNames(int nCols) {
        List<String> names = new ArrayList<>();
        for (int c = 0; c < nCols; c++) {
            names.add("v" + (c + 1));
        }
        return names;
    }

    /**
     * Converts a header cell value to a valid Stata variable name.
     * Rules: start with letter or underscore, max 32 chars,
     * only letters/digits/underscores.
     */
    private static String toSafeVarName(String raw, int colIndex) {
        if (raw == null || raw.trim().isEmpty()) {
            return "v" + (colIndex + 1);
        }

        // Replace spaces and hyphens with underscores
        String s = raw.trim()
            .replaceAll("[ \\-]", "_")
            .replaceAll("[^A-Za-z0-9_]", "");

        // Must start with letter or underscore
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) {
            s = "v" + s;
        }

        // Truncate to 32 characters (Stata max)
        if (s.length() > 32) s = s.substring(0, 32);

        // Final fallback
        if (s.isEmpty()) return "v" + (colIndex + 1);

        return s;
    }
}
