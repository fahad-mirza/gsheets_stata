// =============================================================================
// GsheetsImport.java - gsheets package
// SFI entry point for the import gsheet command.
//
// Args layout (0-indexed):
//   0  spreadsheetId   sheet ID or full URL
//   1  personalDir     c(sysdir_personal) for token cache
//   2  sheetName       sheet/tab name, or "" for first sheet
//   3  range           A1 range, or "" for entire sheet
//   4  firstRow        "1" = first row is variable names
//   5  nThreads        number of parallel threads, "0" = auto
//   6  verbose         "1" = show progress
//
// Macros written back to Stata:
//   gs_status         "ok" or "error"
//   gs_error_msg      error message if gs_status == "error"
//   gs_nobs           number of observations loaded
//   gs_nvars          number of variables loaded
//
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.stata.sfi.Data;
import com.stata.sfi.Macro;
import com.stata.sfi.SFIToolkit;

import java.util.List;

public class GsheetsImport {

    private static final String MACRO_STATUS    = "gs_status";
    private static final String MACRO_ERROR_MSG = "gs_error_msg";
    private static final String MACRO_NOBS      = "gs_nobs";
    private static final String MACRO_NVARS     = "gs_nvars";

    // ------------------------------------------------------------------
    // SFI entry point
    // ------------------------------------------------------------------

    public static int execute(String[] args) {

        if (args == null || args.length < 2) {
            setError("Internal error: GsheetsImport called with too few arguments.");
            return 1;
        }

        String rawId       = args[0].trim();
        String personalDir = args[1].trim();
        String sheetName   = args.length > 2 ? args[2].trim() : "";
        String range       = args.length > 3 ? args[3].trim() : "";
        boolean firstRow   = args.length > 4 && "1".equals(args[4].trim());
        int nThreads       = 0;
        if (args.length > 5 && !args[5].trim().isEmpty()) {
            try { nThreads = Integer.parseInt(args[5].trim()); }
            catch (NumberFormatException ignored) { }
        }
        boolean verbose    = args.length > 6 && "1".equals(args[6].trim());

        try {
            // Resolve spreadsheet ID from URL or bare ID
            String spreadsheetId = MetadataReader.extractSpreadsheetId(rawId);

            // Get auth tokens
            OAuthManager auth  = new OAuthManager(personalDir);
            String accessToken = auth.getValidAccessToken();
            String apiKey      = auth.getApiKey();

            if (accessToken == null && apiKey == null) {
                throw new GsheetsException(
                    "Not authenticated. Run gs4_auth before using import gsheet.");
            }

            // Resolve sheet name -- if not specified, get it from metadata
            if (sheetName.isEmpty()) {
                SpreadsheetMeta meta = MetadataReader.fetch(
                    spreadsheetId, accessToken, apiKey);
                SpreadsheetMeta.SheetMeta defaultSheet = meta.getDefaultSheet();
                if (defaultSheet == null) {
                    throw new GsheetsException(
                        "Could not determine default sheet. " +
                        "Use the sheet() option to specify a sheet name.");
                }
                sheetName = defaultSheet.title;
            }

            if (verbose) {
                SFIToolkit.displayln(
                    "{txt}Reading sheet: {res}" + sheetName);
            }

            // Fetch cell data
            List<List<CellValue>> rows = SheetReader.read(
                spreadsheetId, sheetName, range.isEmpty() ? null : range,
                accessToken, apiKey, nThreads, verbose);

            if (rows.isEmpty()) {
                throw new GsheetsException(
                    "Sheet '" + sheetName + "' contains no data.");
            }

            // Write to Stata
            StataWriter.write(rows, firstRow, verbose);

            // Report results
            long nobs  = Data.getObsCount();
            int  nvars = Data.getVarCount();

            Macro.setLocal(MACRO_STATUS,    "ok");
            Macro.setLocal(MACRO_ERROR_MSG, "");
            Macro.setLocal(MACRO_NOBS,      String.valueOf(nobs));
            Macro.setLocal(MACRO_NVARS,     String.valueOf(nvars));

            SFIToolkit.displayln("{txt}Loaded {res}" + nobs +
                "{txt} observations, {res}" + nvars + "{txt} variable(s).");

            return 0;

        } catch (GsheetsException e) {
            setError(e.getMessage());
            return 1;
        } catch (Exception e) {
            setError("Unexpected error in GsheetsImport: " + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private static void setError(String message) {
        Macro.setLocal(MACRO_STATUS,    "error");
        Macro.setLocal(MACRO_ERROR_MSG, message);
        SFIToolkit.error(message);
    }
}
