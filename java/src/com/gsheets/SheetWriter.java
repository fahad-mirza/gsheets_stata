// =============================================================================
// SheetWriter.java - gsheets package
// Writes a 2D list of strings to a Google Sheet via the Sheets API v4.
//
// Write modes:
//   REPLACE: clear sheet content, write all rows from A1
//   APPEND:  schema-aware append -- reads existing header, reconciles columns,
//            aligns new rows to existing schema (mirrors Stata's append command)
//
// Schema-aware append behaviour (mirrors Stata's append):
//   - Variables in new data AND existing sheet: aligned by column name
//   - Variables in new data NOT in existing sheet: added as new columns
//     (existing rows get empty cells in those columns)
//   - Variables in existing sheet NOT in new data: empty cells written
//     for new rows in those columns
//
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SheetWriter {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    private static final String SHEETS_BASE =
        "https://sheets.googleapis.com/v4/spreadsheets";

    // Max rows per write request -- keeps payload under ~10 MB
    private static final int WRITE_CHUNK_SIZE = 5_000;

    private static final int REQUEST_TIMEOUT_S = 60;

    // Shared HTTP client
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final Gson GSON = new Gson();

    // ------------------------------------------------------------------
    // Write modes
    // ------------------------------------------------------------------

    public enum WriteMode { REPLACE, APPEND }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Writes rows to a Google Sheet.
     * In APPEND mode, performs schema-aware column reconciliation.
     *
     * @param spreadsheetId  target spreadsheet ID
     * @param sheetName      target sheet/tab name
     * @param rows           data rows -- row 0 is header if firstRowType != NONE
     * @param mode           REPLACE or APPEND
     * @param hasHeader      true if rows.get(0) is a header row
     * @param accessToken    OAuth2/service-account token
     * @param verbose        print progress
     */
    public static void write(String spreadsheetId,
                              String sheetName,
                              List<List<String>> rows,
                              WriteMode mode,
                              boolean hasHeader,
                              String generateVarName,
                              String accessToken,
                              boolean verbose)
            throws GsheetsException {

        if (rows == null || rows.isEmpty()) {
            throw new GsheetsException("No data to write.");
        }

        String sheetRef = buildSheetRef(sheetName);

        // Create the sheet tab if it does not already exist
        ensureSheetExists(spreadsheetId, sheetName, accessToken, verbose);

        if (mode == WriteMode.REPLACE) {
            doReplace(spreadsheetId, sheetRef, rows, accessToken, verbose);
        } else {
            doAppend(spreadsheetId, sheetRef, sheetName, rows,
                     hasHeader, generateVarName, accessToken, verbose);
        }
    }

    // ------------------------------------------------------------------
    // REPLACE mode
    // ------------------------------------------------------------------

    private static void doReplace(String spreadsheetId,
                                   String sheetRef,
                                   List<List<String>> rows,
                                   String accessToken,
                                   boolean verbose)
            throws GsheetsException {

        // Clear existing content
        clearSheet(spreadsheetId, sheetRef, accessToken);
        if (verbose) {
            log("Sheet cleared.");
        }

        // Write all rows from A1
        writeChunked(spreadsheetId, sheetRef, rows, 1, accessToken, verbose);
    }

    // ------------------------------------------------------------------
    // APPEND mode -- schema-aware
    // ------------------------------------------------------------------

    private static void doAppend(String spreadsheetId,
                                  String sheetRef,
                                  String sheetName,
                                  List<List<String>> rows,
                                  boolean hasHeader,
                                  String generateVarName,
                                  String accessToken,
                                  boolean verbose)
            throws GsheetsException {

        // Step 1: Read existing sheet header to understand current schema
        List<String> existingHeaders = readHeaderRow(
            spreadsheetId, sheetRef, accessToken);

        boolean sheetIsEmpty = existingHeaders.isEmpty();

        if (sheetIsEmpty) {
            // Sheet has no data -- treat as a replace (write everything)
            if (verbose) {
                log("Sheet is empty. Writing as new data.");
            }
            writeChunked(spreadsheetId, sheetRef, rows, 1, accessToken, verbose);
            return;
        }

        // Step 2: Extract new data headers (if present) and data rows
        List<String> newHeaders;
        List<List<String>> dataRows;

        if (hasHeader && !rows.isEmpty()) {
            newHeaders = new ArrayList<>(rows.get(0));
            dataRows   = rows.subList(1, rows.size());
        } else {
            // No header in new data -- columns are in same order as existing
            // We use existing headers as the base for reconciliation
            newHeaders = new ArrayList<>(existingHeaders);
            dataRows   = rows;
        }

        // If generate() column was requested, add it to newHeaders so
        // reconcileSchemas treats it as a new column to add to the sheet.
        // The actual timestamp values are already in each data row from
        // StataReader -- we just need the header to be known here.
        if (generateVarName != null && !generateVarName.trim().isEmpty()) {
            newHeaders.add(generateVarName);
        }

        if (dataRows.isEmpty()) {
            throw new GsheetsException("No data rows to append.");
        }

        // Step 3: Reconcile schemas
        // Build unified column list: existing columns first, then any new ones
        SchemaReconciliation schema = reconcileSchemas(
            existingHeaders, newHeaders);

        if (verbose) {
            log("Existing columns: " + existingHeaders.size());
            log("New data columns: " + newHeaders.size());
            log("Unified columns:  " + schema.unifiedHeaders.size());
            if (!schema.newColumns.isEmpty()) {
                log("New columns added: " + String.join(", ", schema.newColumns));
            }
            if (!schema.missingInNew.isEmpty()) {
                log("Columns not in new data (will be empty): " +
                    String.join(", ", schema.missingInNew));
            }
        }

        // Step 4: If new columns were added, update the header row in the sheet
        if (!schema.newColumns.isEmpty()) {
            extendHeader(spreadsheetId, sheetRef,
                         schema.unifiedHeaders, accessToken, verbose);
        }

        // Step 5: Reorder new data rows to match unified column order
        List<List<String>> alignedRows = alignRows(dataRows, newHeaders, schema);

        // Step 6: Append aligned rows
        appendRows(spreadsheetId, sheetRef, alignedRows, accessToken, verbose);

        if (verbose) {
            log("Appended " + alignedRows.size() + " rows.");
        }
    }

    // ------------------------------------------------------------------
    // Schema reconciliation
    // ------------------------------------------------------------------

    /**
     * Holds the result of reconciling two column schemas.
     */
    private static class SchemaReconciliation {
        // Final unified column order (existing + any new columns appended)
        List<String> unifiedHeaders = new ArrayList<>();
        // Columns in new data not in existing sheet
        List<String> newColumns     = new ArrayList<>();
        // Columns in existing sheet not in new data
        List<String> missingInNew   = new ArrayList<>();
        // Map: unified column index -> new data column index (-1 if missing)
        int[] newDataColIndex;
    }

    /**
     * Reconciles existing sheet headers with new data headers.
     * Returns a SchemaReconciliation describing how to align rows.
     * Column matching is case-insensitive.
     */
    private static SchemaReconciliation reconcileSchemas(
            List<String> existingHeaders,
            List<String> newHeaders) {

        SchemaReconciliation r = new SchemaReconciliation();

        // Build lookup: lowercase name -> index in new data headers
        Map<String, Integer> newHeaderIndex = new LinkedHashMap<>();
        for (int i = 0; i < newHeaders.size(); i++) {
            newHeaderIndex.put(newHeaders.get(i).toLowerCase().trim(), i);
        }

        // Build lookup: lowercase name -> index in existing headers
        Map<String, Integer> existingHeaderIndex = new LinkedHashMap<>();
        for (int i = 0; i < existingHeaders.size(); i++) {
            existingHeaderIndex.put(
                existingHeaders.get(i).toLowerCase().trim(), i);
        }

        // Start unified list with existing columns in existing order
        r.unifiedHeaders.addAll(existingHeaders);

        // Track which new columns are missing from existing
        for (String newCol : newHeaders) {
            String key = newCol.toLowerCase().trim();
            if (!existingHeaderIndex.containsKey(key)) {
                r.newColumns.add(newCol);
                r.unifiedHeaders.add(newCol); // append at end
            }
        }

        // Track which existing columns are missing from new data
        for (String existingCol : existingHeaders) {
            String key = existingCol.toLowerCase().trim();
            if (!newHeaderIndex.containsKey(key)) {
                r.missingInNew.add(existingCol);
            }
        }

        // Build mapping: unified column index -> new data column index
        r.newDataColIndex = new int[r.unifiedHeaders.size()];
        for (int i = 0; i < r.unifiedHeaders.size(); i++) {
            String key = r.unifiedHeaders.get(i).toLowerCase().trim();
            Integer newIdx = newHeaderIndex.get(key);
            r.newDataColIndex[i] = (newIdx != null) ? newIdx : -1;
        }

        return r;
    }

    /**
     * Reorders new data rows to match the unified column schema.
     * Columns missing in new data are written as empty strings.
     */
    private static List<List<String>> alignRows(
            List<List<String>> dataRows,
            List<String> newHeaders,
            SchemaReconciliation schema) {

        List<List<String>> aligned = new ArrayList<>(dataRows.size());

        for (List<String> row : dataRows) {
            List<String> alignedRow = new ArrayList<>(schema.unifiedHeaders.size());
            for (int i = 0; i < schema.unifiedHeaders.size(); i++) {
                int srcIdx = schema.newDataColIndex[i];
                if (srcIdx >= 0 && srcIdx < row.size()) {
                    alignedRow.add(row.get(srcIdx));
                } else {
                    alignedRow.add(""); // missing in new data -> empty cell
                }
            }
            aligned.add(alignedRow);
        }

        return aligned;
    }

    // ------------------------------------------------------------------
    // Read existing header row
    // ------------------------------------------------------------------

    private static List<String> readHeaderRow(String spreadsheetId,
                                               String sheetRef,
                                               String accessToken)
            throws GsheetsException {

        // Fetch only the first row
        String range = sheetRef + "!1:1";
        String encodedRange = URLEncoder.encode(range, StandardCharsets.UTF_8);
        String url = SHEETS_BASE + "/" + spreadsheetId +
                     "/values/" + encodedRange +
                     "?valueRenderOption=FORMATTED_VALUE";

        HttpResponse<String> response = doGet(url, accessToken);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // If sheet doesn't exist yet, return empty list
            if (response.statusCode() == 400 || response.statusCode() == 404) {
                return new ArrayList<>();
            }
            throw translateHttpError(response.statusCode(), response.body(),
                "reading existing header row");
        }

        try {
            JsonObject obj = JsonParser.parseString(response.body())
                                       .getAsJsonObject();
            if (!obj.has("values")) return new ArrayList<>();

            JsonArray rows = obj.getAsJsonArray("values");
            if (rows.size() == 0) return new ArrayList<>();

            JsonArray headerRow = rows.get(0).getAsJsonArray();
            List<String> headers = new ArrayList<>(headerRow.size());
            for (JsonElement cell : headerRow) {
                headers.add(cell.getAsString());
            }
            return headers;

        } catch (Exception e) {
            throw new GsheetsException(
                "Could not parse existing header row: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Extend existing header with new columns
    // ------------------------------------------------------------------

    /**
     * Updates row 1 of the sheet to include new columns appended at the end.
     * Only called when new data has variables not in the existing sheet.
     */
    private static void extendHeader(String spreadsheetId,
                                      String sheetRef,
                                      List<String> unifiedHeaders,
                                      String accessToken,
                                      boolean verbose)
            throws GsheetsException {

        if (verbose) {
            log("Extending header row to " + unifiedHeaders.size() + " columns...");
        }

        String range = sheetRef + "!A1";
        String encodedRange = URLEncoder.encode(range, StandardCharsets.UTF_8);
        String url = SHEETS_BASE + "/" + spreadsheetId +
                     "/values/" + encodedRange +
                     "?valueInputOption=USER_ENTERED";

        // Wrap headers in a single-row values array
        List<List<String>> headerRow = new ArrayList<>();
        headerRow.add(unifiedHeaders);
        String body = buildValuesBody(range, headerRow);

        HttpResponse<String> response = doPut(url, body, accessToken);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw translateHttpError(response.statusCode(), response.body(),
                "extending header row");
        }
    }

    // ------------------------------------------------------------------
    // Ensure sheet tab exists -- create it if not
    // ------------------------------------------------------------------

    /**
     * Checks if the named sheet tab exists in the spreadsheet.
     * If it does not exist, creates it via spreadsheets.batchUpdate addSheet.
     * This allows export_gsheet to create new tabs automatically without
     * requiring the user to manually add them in Google Sheets first.
     */
    private static void ensureSheetExists(String spreadsheetId,
                                           String sheetName,
                                           String accessToken,
                                           boolean verbose)
            throws GsheetsException {

        // Step 1: get spreadsheet metadata to check existing sheets
        String metaUrl = SHEETS_BASE + "/" + spreadsheetId +
                         "?fields=sheets.properties.title";

        HttpResponse<String> metaResponse = doGet(metaUrl, accessToken);

        if (metaResponse.statusCode() < 200 || metaResponse.statusCode() >= 300) {
            throw translateHttpError(metaResponse.statusCode(),
                metaResponse.body(), "checking spreadsheet metadata");
        }

        // Step 2: parse sheet titles and check if ours exists
        try {
            JsonObject obj = JsonParser.parseString(metaResponse.body())
                                       .getAsJsonObject();

            if (obj.has("sheets")) {
                for (JsonElement el : obj.getAsJsonArray("sheets")) {
                    JsonObject props = el.getAsJsonObject()
                                        .getAsJsonObject("properties");
                    if (props.has("title")) {
                        String title = props.get("title").getAsString();
                        if (title.equalsIgnoreCase(sheetName)) {
                            // Sheet already exists -- nothing to do
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new GsheetsException(
                "Could not parse spreadsheet metadata: " + e.getMessage());
        }

        // Step 3: sheet does not exist -- create it via batchUpdate
        if (verbose) {
            log("Sheet '" + sheetName + "' not found. Creating...");
        }

        String batchUrl = SHEETS_BASE + "/" + spreadsheetId + ":batchUpdate";

        JsonObject addSheetRequest = new JsonObject();
        JsonObject addSheet        = new JsonObject();
        JsonObject properties      = new JsonObject();
        properties.addProperty("title", sheetName);
        addSheet.add("properties", properties);
        addSheetRequest.add("addSheet", addSheet);

        JsonArray requests = new JsonArray();
        requests.add(addSheetRequest);

        JsonObject batchBody = new JsonObject();
        batchBody.add("requests", requests);

        HttpResponse<String> batchResponse = doPost(
            batchUrl, GSON.toJson(batchBody), accessToken);

        if (batchResponse.statusCode() < 200 ||
            batchResponse.statusCode() >= 300) {
            throw translateHttpError(batchResponse.statusCode(),
                batchResponse.body(),
                "creating sheet '" + sheetName + "'");
        }

        if (verbose) {
            log("Sheet '" + sheetName + "' created.");
        }
    }

    // ------------------------------------------------------------------
    // Clear sheet content
    // ------------------------------------------------------------------

    private static void clearSheet(String spreadsheetId,
                                    String sheetRef,
                                    String accessToken)
            throws GsheetsException {

        String encodedRef = URLEncoder.encode(sheetRef, StandardCharsets.UTF_8);
        String url = SHEETS_BASE + "/" + spreadsheetId +
                     "/values/" + encodedRef + ":clear";

        HttpResponse<String> response = doPost(url, "{}", accessToken);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw translateHttpError(response.statusCode(), response.body(),
                "clearing sheet");
        }
    }

    // ------------------------------------------------------------------
    // Write in chunks (replace mode -- from specific start row)
    // ------------------------------------------------------------------

    private static void writeChunked(String spreadsheetId,
                                      String sheetRef,
                                      List<List<String>> rows,
                                      int startRow,
                                      String accessToken,
                                      boolean verbose)
            throws GsheetsException {

        int totalRows = rows.size();
        int written   = 0;
        int chunkNum  = 0;

        while (written < totalRows) {
            int end = Math.min(written + WRITE_CHUNK_SIZE, totalRows);
            List<List<String>> chunk = rows.subList(written, end);
            int rowStart = startRow + written;

            String range = sheetRef + "!A" + rowStart;
            String encodedRange = URLEncoder.encode(range, StandardCharsets.UTF_8);
            String url = SHEETS_BASE + "/" + spreadsheetId +
                         "/values/" + encodedRange +
                         "?valueInputOption=USER_ENTERED";

            String body = buildValuesBody(range, chunk);
            HttpResponse<String> response = doPut(url, body, accessToken);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw translateHttpError(response.statusCode(), response.body(),
                    "writing rows " + rowStart + "-" + (rowStart + chunk.size() - 1));
            }

            written += chunk.size();
            chunkNum++;

            if (verbose && totalRows > WRITE_CHUNK_SIZE) {
                log("Written chunk " + chunkNum +
                    " (" + written + "/" + totalRows + " rows)...");
            }
        }
    }

    // ------------------------------------------------------------------
    // Append rows via API append endpoint
    // ------------------------------------------------------------------

    private static void appendRows(String spreadsheetId,
                                    String sheetRef,
                                    List<List<String>> rows,
                                    String accessToken,
                                    boolean verbose)
            throws GsheetsException {

        int totalRows = rows.size();
        int written   = 0;

        while (written < totalRows) {
            int end = Math.min(written + WRITE_CHUNK_SIZE, totalRows);
            List<List<String>> chunk = rows.subList(written, end);

            String range = sheetRef + "!A1";
            String encodedRange = URLEncoder.encode(range, StandardCharsets.UTF_8);
            String url = SHEETS_BASE + "/" + spreadsheetId +
                         "/values/" + encodedRange +
                         ":append?valueInputOption=USER_ENTERED" +
                         "&insertDataOption=INSERT_ROWS";

            String body = buildValuesBody(range, chunk);
            HttpResponse<String> response = doPost(url, body, accessToken);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw translateHttpError(response.statusCode(), response.body(),
                    "appending rows");
            }

            written += chunk.size();

            if (verbose && totalRows > WRITE_CHUNK_SIZE) {
                log("Appended " + written + "/" + totalRows + " rows...");
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON body builder
    // ------------------------------------------------------------------

    private static String buildValuesBody(String range,
                                           List<List<String>> rows) {
        JsonObject body = new JsonObject();
        body.addProperty("range", range);
        body.addProperty("majorDimension", "ROWS");

        JsonArray values = new JsonArray();
        for (List<String> row : rows) {
            JsonArray rowArr = new JsonArray();
            for (String cell : row) {
                rowArr.add(cell != null ? cell : "");
            }
            values.add(rowArr);
        }
        body.add("values", values);
        return GSON.toJson(body);
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private static HttpResponse<String> doGet(String url,
                                               String accessToken)
            throws GsheetsException {
        return doRequest("GET", url, null, accessToken);
    }

    private static HttpResponse<String> doPost(String url,
                                                String jsonBody,
                                                String accessToken)
            throws GsheetsException {
        return doRequest("POST", url, jsonBody, accessToken);
    }

    private static HttpResponse<String> doPut(String url,
                                               String jsonBody,
                                               String accessToken)
            throws GsheetsException {
        return doRequest("PUT", url, jsonBody, accessToken);
    }

    private static HttpResponse<String> doRequest(String method,
                                                    String url,
                                                    String jsonBody,
                                                    String accessToken)
            throws GsheetsException {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .header("Accept", "application/json");

        if (accessToken != null && !accessToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }

        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json");
            HttpRequest.BodyPublisher body = (jsonBody != null)
                ? HttpRequest.BodyPublishers.ofString(jsonBody)
                : HttpRequest.BodyPublishers.noBody();
            if ("POST".equals(method)) {
                builder.POST(body);
            } else {
                builder.PUT(body);
            }
        }

        try {
            return HTTP_CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new GsheetsException(
                "Network error during export: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String buildSheetRef(String sheetName) {
        if (sheetName != null && !sheetName.trim().isEmpty()) {
            return "'" + sheetName.replace("'", "\\'") + "'";
        }
        return "Sheet1";
    }

    private static void log(String msg) {
        com.stata.sfi.SFIToolkit.displayln("{txt}" + msg);
    }

    private static GsheetsException translateHttpError(int status,
                                                         String body,
                                                         String context) {
        String detail = extractErrorMessage(body);
        switch (status) {
            case 400: return new GsheetsException(
                "Bad request (400) while " + context + ": " + detail);
            case 401: return new GsheetsException(
                "Not authorised (401). Run gs4_auth to re-authenticate.");
            case 403: return new GsheetsException(
                "Access denied (403) while " + context + ": " + detail +
                ". Ensure your account has edit access to this sheet.");
            case 404: return new GsheetsException(
                "Sheet not found (404) while " + context + ": " + detail);
            case 429: return new GsheetsException(
                "Rate limit exceeded (429). Wait a moment and try again.");
            default: return new GsheetsException(
                "API error (HTTP " + status + ") while " + context +
                ": " + detail);
        }
    }

    private static String extractErrorMessage(String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("error")) {
                JsonObject err = obj.getAsJsonObject("error");
                if (err.has("message")) return err.get("message").getAsString();
            }
        } catch (Exception ignored) { }
        return body.length() > 150 ? body.substring(0, 150) : body;
    }
}
