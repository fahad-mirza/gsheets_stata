// =============================================================================
// GsheetsExport.java - gsheets package
// SFI entry point for the export_gsheet command.
//
// Args layout (0-indexed):
//   0  spreadsheetId   sheet ID or full URL
//   1  personalDir     c(sysdir_personal) for token cache
//   2  sheetName       target sheet/tab name
//   3  writeMode       "replace" | "append"
//   4  firstRowType    "variables" | "varlabels" | "none"
//   5  touseVar        name of touse marker variable (from marksample)
//   6  verbose         "1" = show progress
//
// Macros written back to Stata:
//   gs_status         "ok" or "error"
//   gs_error_msg      error message if gs_status == "error"
//   gs_nrows_written  number of data rows written (excluding header)
//
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.stata.sfi.Macro;
import com.stata.sfi.SFIToolkit;

import java.util.List;

public class GsheetsExport {

    private static final String MACRO_STATUS       = "gs_status";
    private static final String MACRO_ERROR_MSG    = "gs_error_msg";
    private static final String MACRO_NROWS        = "gs_nrows_written";

    // ------------------------------------------------------------------
    // SFI entry point
    // ------------------------------------------------------------------

    public static int execute(String[] args) {

        if (args == null || args.length < 2) {
            setError("Internal error: GsheetsExport called with too few arguments.");
            return 1;
        }

        String rawId       = args[0].trim();
        String personalDir = args[1].trim();
        String sheetName   = args.length > 2 ? args[2].trim() : "Sheet1";
        String writeModeStr = args.length > 3 ? args[3].trim() : "replace";
        String firstRowStr = args.length > 4 ? args[4].trim() : "variables";
        String touseVar      = args.length > 5 ? args[5].trim() : "";
        boolean verbose      = args.length > 6 && "1".equals(args[6].trim());
        String exportVarsPipe  = args.length > 7 ? args[7].trim() : "";
        String generateVarName = args.length > 8 ? args[8].trim() : "";

        try {
            // Resolve spreadsheet ID
            String spreadsheetId = MetadataReader.extractSpreadsheetId(rawId);

            // Get auth tokens
            OAuthManager auth  = new OAuthManager(personalDir);
            String accessToken = auth.getValidAccessToken();

            if (accessToken == null) {
                throw new GsheetsException(
                    "export_gsheet requires OAuth2 or service account authentication. " +
                    "API key mode is read-only and cannot write to sheets. " +
                    "Run: gs4_auth, clientid(\"...\") clientsecret(\"...\")");
            }

            // Parse write mode
            SheetWriter.WriteMode writeMode;
            if ("append".equalsIgnoreCase(writeModeStr)) {
                writeMode = SheetWriter.WriteMode.APPEND;
            } else {
                writeMode = SheetWriter.WriteMode.REPLACE;
            }

            // Parse firstrow type
            StataReader.FirstRowType firstRowType;
            if ("varlabels".equalsIgnoreCase(firstRowStr)) {
                firstRowType = StataReader.FirstRowType.VARLABELS;
            } else if ("none".equalsIgnoreCase(firstRowStr) ||
                       "nolabel".equalsIgnoreCase(firstRowStr)) {
                firstRowType = StataReader.FirstRowType.NONE;
            } else {
                firstRowType = StataReader.FirstRowType.VARIABLES; // default
            }

            if (verbose) {
                SFIToolkit.displayln("{txt}Reading dataset from Stata...");
            }

            // Parse pipe-delimited varlist (built in .ado to exclude tempvars)
            String[] exportVars = null;
            if (!exportVarsPipe.isEmpty()) {
                exportVars = exportVarsPipe.split("\\|");
            }

            // Read data from Stata (respects if/in via touse marker)
            List<List<String>> rows = StataReader.read(
                touseVar.isEmpty() ? null : touseVar,
                exportVars,
                firstRowType,
                generateVarName.isEmpty() ? null : generateVarName,
                verbose);

            // Count data rows (excluding header)
            int dataRows = rows.size();
            if (firstRowType != StataReader.FirstRowType.NONE && dataRows > 0) {
                dataRows--; // subtract header row
            }

            if (verbose) {
                SFIToolkit.displayln("{txt}Writing to sheet: {res}" + sheetName);
            }

            // Write to Google Sheets
            // hasHeader: true if rows.get(0) is a header row (needed for
            // schema-aware append to correctly identify column names)
            boolean hasHeader = (firstRowType != StataReader.FirstRowType.NONE);
            SheetWriter.write(spreadsheetId, sheetName, rows,
                             writeMode, hasHeader,
                             generateVarName.isEmpty() ? null : generateVarName,
                             accessToken, verbose);

            // Return results
            Macro.setLocal(MACRO_STATUS,    "ok");
            Macro.setLocal(MACRO_ERROR_MSG, "");
            Macro.setLocal(MACRO_NROWS,     String.valueOf(dataRows));

            SFIToolkit.displayln("{txt}Exported {res}" + dataRows +
                "{txt} observation(s) to sheet '{res}" + sheetName + "{txt}'.");

            return 0;

        } catch (GsheetsException e) {
            setError(e.getMessage());
            return 1;
        } catch (Exception e) {
            setError("Unexpected error in GsheetsExport: " + e.getMessage());
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
