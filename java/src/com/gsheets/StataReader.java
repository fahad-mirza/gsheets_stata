// =============================================================================
// StataReader.java - gsheets package
// Reads the current Stata dataset into a 2D list of strings for export.
// Respects the touse marker variable set by marksample in the .ado file,
// so if/in subsetting is handled automatically by Stata before Java is called.
// This is the inverse of StataWriter -- Stata -> Java direction.
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.stata.sfi.Data;
import com.stata.sfi.Missing;
import com.stata.sfi.SFIToolkit;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StataReader {

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Reads the current Stata dataset into a 2D list of strings.
     * Each inner list is one row (observation). The first row is
     * the header (variable names or labels) if firstRowType != NONE.
     *
     * @param touseVar      name of the touse marker variable (from marksample)
     *                      -- only observations where touse==1 are included.
     *                      Pass null or empty to include all observations.
     * @param firstRowType  what to put in the header row
     * @param verbose       print progress messages
     * @return              2D list of strings, header first if requested
     */
    /**
     * @param exportVars  explicit list of variable names to export,
     *                    built in the .ado file using ds to exclude tempvars.
     *                    If null, all variables except touseVar are exported.
     */
    public static List<List<String>> read(String touseVar,
                                           String[] exportVars,
                                           FirstRowType firstRowType,
                                           String generateVarName,
                                           boolean verbose)
            throws GsheetsException {

        int nVars = Data.getVarCount();
        long nObs = Data.getObsCount();

        if (nVars == 0) {
            throw new GsheetsException(
                "No variables in current dataset. Nothing to export.");
        }
        if (nObs == 0) {
            throw new GsheetsException(
                "No observations in current dataset. Nothing to export.");
        }

        // Resolve touse variable index -- -1 means include all
        int touseIdx = -1;
        if (touseVar != null && !touseVar.trim().isEmpty()) {
            touseIdx = Data.getVarIndex(touseVar.trim());
            if (touseIdx < 0) {
                throw new GsheetsException(
                    "Internal error: touse variable '" + touseVar +
                    "' not found in dataset.");
            }
        }

        // Get all variable indices and names
        int[] varIndices;
        String[] varNames;
        String[] varLabels;
        boolean[] isString;

        // Build list of exportable variable SFI indices.
        // If an explicit varlist was passed from the .ado file (built using ds
        // which excludes tempvars), use it. Otherwise fall back to all vars
        // minus the touse marker. The explicit varlist approach is immune to
        // any tempvar naming convention -- Stata knows its own tempvars.
        List<Integer> exportVarList = new ArrayList<>();

        if (exportVars != null && exportVars.length > 0) {
            // Use explicit varlist from .ado -- guaranteed no tempvars
            for (String varName : exportVars) {
                String v = varName.trim();
                if (v.isEmpty()) continue;
                int idx = Data.getVarIndex(v);
                if (idx >= 0) {
                    exportVarList.add(idx);
                }
            }
            if (exportVarList.isEmpty()) {
                throw new GsheetsException(
                    "No valid variables found in export varlist.");
            }
        } else {
            // Fallback: all vars except touse marker
            String touseVarName = (touseVar != null) ? touseVar.trim() : "";
            for (int v = 0; v < nVars; v++) {
                String name = Data.getVarName(v + 1);
                if (name != null && !name.equals(touseVarName)) {
                    exportVarList.add(v + 1);
                }
            }
        }

        // Rebuild arrays using only exportable variables
        int exportNVars = exportVarList.size();
        varIndices = new int[exportNVars];
        varNames   = new String[exportNVars];
        varLabels  = new String[exportNVars];
        isString   = new boolean[exportNVars];
        nVars      = exportNVars;

        for (int v = 0; v < exportNVars; v++) {
            varIndices[v] = exportVarList.get(v);
            varNames[v]   = Data.getVarName(varIndices[v]);
            varLabels[v]  = Data.getVarLabel(varIndices[v]);
            if (varLabels[v] == null || varLabels[v].trim().isEmpty()) {
                varLabels[v] = varNames[v];
            }
            isString[v] = Data.isVarTypeStr(varIndices[v]);
        }

        List<List<String>> result = new ArrayList<>();

        // ------------------------------------------------------------------
        // Generate column setup -- Stata %tc formatted datetime
        // Format: DDmonYYYY HH:MM:SS (e.g. 27mar2026 14:32:07)
        // Lowercase month to match Stata's display convention exactly.
        // ------------------------------------------------------------------
        boolean hasGenerateCol = (generateVarName != null &&
                                   !generateVarName.trim().isEmpty());
        String generateTimestamp = "";

        if (hasGenerateCol) {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            // Format day as two digits, month as 3-letter abbreviation,
            // year as 4 digits, then time -- all lowercase to match Stata
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(
                "ddMMMyyyy HH:mm:ss", Locale.ENGLISH);
            generateTimestamp = now.format(fmt).toLowerCase(Locale.ENGLISH);
        }

        // Add header row if requested
        if (firstRowType == FirstRowType.VARIABLES) {
            List<String> header = new ArrayList<>(nVars);
            for (String name : varNames) header.add(name);
            if (hasGenerateCol) header.add(generateVarName);
            result.add(header);
        } else if (firstRowType == FirstRowType.VARLABELS) {
            List<String> header = new ArrayList<>(nVars);
            for (String label : varLabels) header.add(label);
            if (hasGenerateCol) header.add(generateVarName);
            result.add(header);
        }

        // Count selected observations for verbose output
        long selectedObs = 0;

        // Read data observations
        for (long obs = 1; obs <= nObs; obs++) {

            // Check touse marker -- skip if not selected
            if (touseIdx >= 0) {
                double touseVal = Data.getNum(touseIdx, obs);
                if (Double.isNaN(touseVal) || touseVal == 0) continue;
            }

            selectedObs++;
            List<String> row = new ArrayList<>(nVars);

            for (int v = 0; v < nVars; v++) {
                int vi = varIndices[v];
                String cellValue;

                if (isString[v]) {
                    String s = Data.getStr(vi, obs);
                    cellValue = (s == null) ? "" : s;
                } else {
                    double d = Data.getNum(vi, obs);
                    if (Data.isValueMissing(d)) {
                        cellValue = ""; // missing -> empty cell in Sheets
                    } else {
                        // Format number: drop unnecessary .0 for integers
                        if (d == Math.floor(d) && !Double.isInfinite(d) &&
                            Math.abs(d) < 1e15) {
                            cellValue = String.valueOf((long) d);
                        } else {
                            cellValue = String.valueOf(d);
                        }
                    }
                }

                row.add(cellValue);
            }

            // Add generate column timestamp to each data row
            if (hasGenerateCol) {
                row.add(generateTimestamp);
            }

            result.add(row);
        }

        if (verbose) {
            SFIToolkit.displayln("{txt}Prepared {res}" + selectedObs +
                "{txt} observation(s), {res}" + nVars + "{txt} variable(s) for export.");
        }

        return result;
    }

    // ------------------------------------------------------------------
    // FirstRowType enum
    // ------------------------------------------------------------------

    public enum FirstRowType {
        NONE,        // no header row
        VARIABLES,   // variable names as header
        VARLABELS    // variable labels as header (falls back to name)
    }
}
