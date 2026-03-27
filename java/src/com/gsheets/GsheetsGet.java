// =============================================================================
// GsheetsGet.java - gsheets package
// SFI entry point for the gs4_get command.
// Fetches spreadsheet metadata and writes it back to Stata via macros.
//
// Args layout (0-indexed):
//   0  spreadsheetId   sheet ID or full URL
//   1  personalDir     c(sysdir_personal) for token cache
//   2  verbose         "1" = show sheet list, "0" = silent
//
// Macros written back to Stata (r()):
//   gs_title          spreadsheet title
//   gs_id             spreadsheet ID
//   gs_nsheets        number of sheets
//   gs_sheet_names    pipe-delimited sheet names  e.g. "Sheet1|Data|Summary"
//   gs_status         "ok" or "error"
//   gs_error_msg      error message if gs_status == "error"
//
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.stata.sfi.Macro;
import com.stata.sfi.SFIToolkit;

public class GsheetsGet {

    private static final String MACRO_STATUS      = "gs_status";
    private static final String MACRO_ERROR_MSG   = "gs_error_msg";
    private static final String MACRO_TITLE       = "gs_title";
    private static final String MACRO_ID          = "gs_id";
    private static final String MACRO_NSHEETS     = "gs_nsheets";
    private static final String MACRO_SHEET_NAMES = "gs_sheet_names";

    // ------------------------------------------------------------------
    // SFI entry point
    // ------------------------------------------------------------------

    public static int execute(String[] args) {

        if (args == null || args.length < 2) {
            setError("Internal error: GsheetsGet called with too few arguments.");
            return 1;
        }

        String rawId       = args[0].trim();
        String personalDir = args[1].trim();
        boolean verbose    = args.length > 2 && "1".equals(args[2].trim());

        try {
            // Resolve sheet ID from bare ID or full URL
            String spreadsheetId = MetadataReader.extractSpreadsheetId(rawId);

            // Get valid token (handles refresh automatically)
            OAuthManager auth  = new OAuthManager(personalDir);
            String accessToken = auth.getValidAccessToken();
            String apiKey      = auth.getApiKey();

            if (accessToken == null && apiKey == null) {
                throw new GsheetsException(
                    "Not authenticated. Run gs4_auth before using gs4_get.");
            }

            // Fetch metadata
            SpreadsheetMeta meta = MetadataReader.fetch(
                spreadsheetId, accessToken, apiKey);

            // Write results to Stata macros
            Macro.setLocal(MACRO_STATUS,      "ok");
            Macro.setLocal(MACRO_TITLE,       meta.title);
            Macro.setLocal(MACRO_ID,          meta.spreadsheetId);
            Macro.setLocal(MACRO_NSHEETS,     String.valueOf(meta.getSheetCount()));
            Macro.setLocal(MACRO_SHEET_NAMES, meta.getSheetNamesPipeDelimited());
            Macro.setLocal(MACRO_ERROR_MSG,   "");

            // Display output
            SFIToolkit.displayln("{txt}Spreadsheet: {res}" + meta.title);
            SFIToolkit.displayln("{txt}ID:          {res}" + meta.spreadsheetId);
            SFIToolkit.displayln("{txt}Sheets:      {res}" + meta.getSheetCount());

            if (verbose) {
                SFIToolkit.displayln("{txt}");
                SFIToolkit.displayln("{txt}Sheet list:");
                for (int i = 0; i < meta.sheets.size(); i++) {
                    SpreadsheetMeta.SheetMeta s = meta.sheets.get(i);
                    String hidden = s.hidden ? " (hidden)" : "";
                    String dims   = s.rowCount > 0
                        ? "  [" + s.rowCount + " x " + s.columnCount + "]"
                        : "";
                    SFIToolkit.displayln("{txt}  " + (i + 1) + ".  {res}" +
                        s.title + "{txt}" + dims + hidden);
                }
            }

            return 0;

        } catch (GsheetsException e) {
            setError(e.getMessage());
            return 1;
        } catch (Exception e) {
            setError("Unexpected error in GsheetsGet: " + e.getMessage());
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
