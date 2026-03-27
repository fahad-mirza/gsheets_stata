// =============================================================================
// MetadataReader.java - gsheets package
// Calls the Sheets API spreadsheets.get endpoint and parses the response
// into a SpreadsheetMeta object.
// Used by gs4_get (Stage 2) and will also be used by SheetReader (Stage 3)
// to get sheet dimensions before reading data.
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class MetadataReader {

    // Fields we request -- keeps response payload small
    private static final String FIELDS =
        "spreadsheetId,properties.title," +
        "sheets(properties(sheetId,title,index,sheetType,hidden," +
        "gridProperties(rowCount,columnCount)))";

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Fetches spreadsheet metadata for the given spreadsheet ID.
     *
     * @param spreadsheetId  the Google Sheets spreadsheet ID
     * @param accessToken    OAuth2/service-account token, or null for API key
     * @param apiKey         API key, or null for token mode
     * @return               parsed SpreadsheetMeta
     */
    public static SpreadsheetMeta fetch(String spreadsheetId,
                                         String accessToken,
                                         String apiKey)
            throws GsheetsException {

        validateSpreadsheetId(spreadsheetId);

        String path = "/" + spreadsheetId + "?fields=" + urlEncode(FIELDS);
        JsonObject response = GoogleSheetsClient.get(path, accessToken, apiKey);

        return parse(response);
    }

    // ------------------------------------------------------------------
    // JSON parsing
    // ------------------------------------------------------------------

    private static SpreadsheetMeta parse(JsonObject response)
            throws GsheetsException {

        SpreadsheetMeta meta = new SpreadsheetMeta();

        // Spreadsheet ID
        meta.spreadsheetId = getString(response, "spreadsheetId", "");

        // Title lives inside properties object
        if (response.has("properties")) {
            JsonObject props = response.getAsJsonObject("properties");
            meta.title = getString(props, "title", "(untitled)");
        } else {
            meta.title = "(untitled)";
        }

        // Sheets array
        if (response.has("sheets")) {
            JsonArray sheetsArr = response.getAsJsonArray("sheets");
            for (JsonElement el : sheetsArr) {
                JsonObject sheetObj = el.getAsJsonObject();
                SpreadsheetMeta.SheetMeta sheet = parseSheet(sheetObj);
                meta.sheets.add(sheet);
            }
        }

        if (meta.sheets.isEmpty()) {
            throw new GsheetsException(
                "Spreadsheet '" + meta.title + "' contains no sheets. " +
                "Verify the spreadsheet ID is correct.");
        }

        return meta;
    }

    private static SpreadsheetMeta.SheetMeta parseSheet(JsonObject sheetObj)
            throws GsheetsException {

        SpreadsheetMeta.SheetMeta sheet = new SpreadsheetMeta.SheetMeta();

        if (!sheetObj.has("properties")) {
            throw new GsheetsException(
                "Unexpected API response: sheet object missing 'properties'.");
        }

        JsonObject props = sheetObj.getAsJsonObject("properties");

        sheet.sheetId   = getInt(props, "sheetId", 0);
        sheet.title     = getString(props, "title", "Sheet1");
        sheet.index     = getInt(props, "index", 0);
        sheet.sheetType = getString(props, "sheetType", "GRID");
        sheet.hidden    = getBoolean(props, "hidden", false);

        // Grid dimensions live inside gridProperties
        if (props.has("gridProperties")) {
            JsonObject grid = props.getAsJsonObject("gridProperties");
            sheet.rowCount    = getInt(grid, "rowCount", 0);
            sheet.columnCount = getInt(grid, "columnCount", 0);
        }

        return sheet;
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /**
     * Accepts either a bare spreadsheet ID or a full Google Sheets URL.
     * Extracts the ID from the URL if needed.
     * Stores the extracted ID back into the passed string.
     */
    public static String extractSpreadsheetId(String input)
            throws GsheetsException {

        if (input == null || input.trim().isEmpty()) {
            throw new GsheetsException(
                "Spreadsheet ID or URL is empty. " +
                "Provide a sheet ID or full Google Sheets URL.");
        }

        // Strip any surrounding quotes Stata may have passed through
        String s = input.trim();
        while (s.startsWith("\"")) s = s.substring(1);
        while (s.endsWith("\""))   s = s.substring(0, s.length() - 1);
        s = s.trim();

        // Full URL -- extract the ID segment
        if (s.startsWith("http")) {
            // URL form: https://docs.google.com/spreadsheets/d/{ID}/edit...
            int dIdx = s.indexOf("/d/");
            if (dIdx < 0) {
                throw new GsheetsException(
                    "Could not extract spreadsheet ID from URL: " + s +
                    ". Expected a URL containing '/d/{spreadsheetId}'.");
            }
            String after = s.substring(dIdx + 3);
            int slash = after.indexOf('/');
            s = slash > 0 ? after.substring(0, slash) : after;
        }

        // Basic validation: IDs are alphanumeric + hyphens + underscores
        if (!s.matches("[A-Za-z0-9_\\-]+")) {
            throw new GsheetsException(
                "Invalid spreadsheet ID: '" + s + "'. " +
                "IDs contain only letters, numbers, hyphens, and underscores.");
        }

        return s;
    }

    private static void validateSpreadsheetId(String id) throws GsheetsException {
        if (id == null || id.trim().isEmpty()) {
            throw new GsheetsException("Spreadsheet ID is empty.");
        }
    }

    // ------------------------------------------------------------------
    // JSON helper utilities
    // ------------------------------------------------------------------

    private static String getString(JsonObject obj, String key, String def) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return def;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean def) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsBoolean();
        }
        return def;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
