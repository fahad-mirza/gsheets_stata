// =============================================================================
// SheetReader.java - gsheets package
// Fetches cell data from the Sheets API values.get endpoint.
//
// Strategy:
//   1. Get actual populated row count via cheap column-A preflight
//   2. If rows <= CHUNK_SIZE: single request for the whole sheet
//   3. If rows >  CHUNK_SIZE: build exact non-overlapping row-anchored
//      ranges for ALL chunks and fire them in parallel simultaneously
//
// This avoids any ambiguity about what rows the "first chunk" covers.
// All chunk ranges are derived from the known row count -- no speculation,
// no overlap, no duplicate data.
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class SheetReader {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    private static final String VALUES_BASE =
        "https://sheets.googleapis.com/v4/spreadsheets";

    private static final int CHUNK_SIZE       = 10_000;
    private static final int DEFAULT_MAX_THREADS = 6;
    private static final int REQUEST_TIMEOUT_S   = 60;

    // ------------------------------------------------------------------
    // Shared HTTP client -- one instance, HTTP/2 connection pool
    // ------------------------------------------------------------------
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    // ThreadLocal Gson -- one parser per thread, no locking needed
    private static final ThreadLocal<Gson> GSON =
        ThreadLocal.withInitial(Gson::new);

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    public static List<List<CellValue>> read(String spreadsheetId,
                                              String sheetName,
                                              String range,
                                              String accessToken,
                                              String apiKey,
                                              int nThreads,
                                              boolean verbose)
            throws GsheetsException {

        // Build the sheet qualifier (e.g. 'Sheet2')
        String sheet = buildSheetQualifier(sheetName);

        // Step 1: get actual populated row count via cheap column-A fetch
        int actualRows = getActualRowCount(
            spreadsheetId, sheet, accessToken, apiKey);

        if (verbose) {
            com.stata.sfi.SFIToolkit.displayln(
                "{txt}Populated rows: {res}" + actualRows);
        }

        if (actualRows == 0) {
            return new ArrayList<>();
        }

        // Step 2: if user specified a range, honour it directly
        if (range != null && !range.trim().isEmpty()) {
            String fullRange = sheet + "!" + range;
            if (verbose) {
                com.stata.sfi.SFIToolkit.displayln(
                    "{txt}Reading range: {res}" + fullRange);
            }
            return fetchRange(spreadsheetId, fullRange, accessToken, apiKey);
        }

        // Step 3: small sheet -- single request
        if (actualRows <= CHUNK_SIZE) {
            if (verbose) {
                com.stata.sfi.SFIToolkit.displayln(
                    "{txt}Reading range: {res}" + sheet);
            }
            List<List<CellValue>> rows = fetchRange(
                spreadsheetId, sheet, accessToken, apiKey);
            if (verbose) {
                com.stata.sfi.SFIToolkit.displayln(
                    "{txt}Retrieved: {res}" + rows.size() + "{txt} rows");
            }
            return rows;
        }

        // Step 4: large sheet -- build ALL chunks from known row count
        // then fire them all in parallel simultaneously
        int maxThreads = resolveThreadCount(nThreads);

        if (verbose) {
            com.stata.sfi.SFIToolkit.displayln(
                "{txt}Large sheet detected. Reading in parallel (" +
                maxThreads + " threads)...");
        }

        List<List<CellValue>> allRows = fetchAllParallel(
            spreadsheetId, sheet, actualRows, maxThreads,
            accessToken, apiKey);

        if (verbose) {
            com.stata.sfi.SFIToolkit.displayln(
                "{txt}Retrieved: {res}" + allRows.size() + "{txt} rows total");
        }

        return allRows;
    }

    // ------------------------------------------------------------------
    // Get actual populated row count (cheap preflight)
    // ------------------------------------------------------------------

    /**
     * Fetches only column A to count actually populated rows.
     * Far cheaper than reading all data -- just counts the response rows.
     * Returns the number of rows the API reports as populated.
     */
    private static int getActualRowCount(String spreadsheetId,
                                          String sheet,
                                          String accessToken,
                                          String apiKey)
            throws GsheetsException {

        String countRange = sheet + "!A:A";
        String url = buildUrl(spreadsheetId, countRange, apiKey);
        HttpResponse<String> response = doGet(url, accessToken);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw translateHttpError(response.statusCode(), response.body());
        }

        try {
            JsonObject obj = GSON.get().fromJson(response.body(),
                                                  JsonObject.class);
            if (obj == null || !obj.has("values")) return 0;
            return obj.getAsJsonArray("values").size();
        } catch (Exception e) {
            throw new GsheetsException(
                "Could not determine sheet row count: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Parallel fetch -- all chunks from row 1 to actualRows
    // ------------------------------------------------------------------

    /**
     * Builds exact non-overlapping row-anchored ranges covering rows
     * 1 through actualRows, fires them all simultaneously via
     * CompletableFuture, and reassembles in order.
     *
     * Each chunk range: sheet!A{start}:ZZ{end}
     * No chunk overlaps. No chunk exceeds actualRows.
     */
    private static List<List<CellValue>> fetchAllParallel(
            String spreadsheetId,
            String sheet,
            int actualRows,
            int maxThreads,
            String accessToken,
            String apiKey)
            throws GsheetsException {

        // Build all chunk ranges
        List<String> chunkRanges = new ArrayList<>();
        int startRow = 1;
        while (startRow <= actualRows) {
            int endRow = Math.min(startRow + CHUNK_SIZE - 1, actualRows);
            chunkRanges.add(sheet + "!A" + startRow + ":ZZ" + endRow);
            startRow = endRow + 1;
        }

        ExecutorService pool = Executors.newFixedThreadPool(maxThreads);
        try {
            // Fire all chunk requests simultaneously
            // Each CompletableFuture: HTTP fetch + JSON parse on same thread
            List<CompletableFuture<List<List<CellValue>>>> futures =
                chunkRanges.stream()
                    .map(chunkRange -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return fetchRange(spreadsheetId, chunkRange,
                                             accessToken, apiKey);
                        } catch (GsheetsException e) {
                            throw new RuntimeException(e);
                        }
                    }, pool))
                    .collect(Collectors.toList());

            // Collect results in chunk order (preserves row sequence)
            List<List<CellValue>> allRows = new ArrayList<>(actualRows);
            for (CompletableFuture<List<List<CellValue>>> future : futures) {
                try {
                    allRows.addAll(future.join());
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof GsheetsException) {
                        throw (GsheetsException) cause;
                    }
                    throw new GsheetsException(
                        "Error fetching chunk: " + e.getMessage());
                }
            }

            return allRows;

        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Single range fetch
    // ------------------------------------------------------------------

    private static List<List<CellValue>> fetchRange(String spreadsheetId,
                                                     String range,
                                                     String accessToken,
                                                     String apiKey)
            throws GsheetsException {

        String url = buildUrl(spreadsheetId, range, apiKey);
        HttpResponse<String> response = doGet(url, accessToken);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() == 400 &&
                response.body().contains("exceeds grid limits")) {
                return new ArrayList<>();
            }
            throw translateHttpError(response.statusCode(), response.body());
        }

        return parseValuesResponse(response.body());
    }

    // ------------------------------------------------------------------
    // HTTP GET
    // ------------------------------------------------------------------

    private static HttpResponse<String> doGet(String url,
                                               String accessToken)
            throws GsheetsException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .header("Accept", "application/json");

        if (accessToken != null && !accessToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }

        try {
            return HTTP_CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new GsheetsException(
                "Network error reading sheet data: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // JSON parsing -- ThreadLocal<Gson> for thread safety
    // ------------------------------------------------------------------

    private static List<List<CellValue>> parseValuesResponse(String body)
            throws GsheetsException {

        JsonObject obj;
        try {
            obj = GSON.get().fromJson(body, JsonObject.class);
        } catch (Exception e) {
            throw new GsheetsException(
                "Could not parse sheet data response: " + e.getMessage());
        }

        List<List<CellValue>> rows = new ArrayList<>();
        if (obj == null || !obj.has("values")) return rows;

        for (JsonElement rowEl : obj.getAsJsonArray("values")) {
            JsonArray rowArr = rowEl.getAsJsonArray();
            List<CellValue> row = new ArrayList<>(rowArr.size());
            for (JsonElement cellEl : rowArr) {
                if (cellEl.isJsonNull() || cellEl.getAsString().isEmpty()) {
                    row.add(CellValue.empty());
                } else {
                    row.add(CellValue.of(cellEl.getAsString()));
                }
            }
            rows.add(row);
        }

        return rows;
    }

    // ------------------------------------------------------------------
    // URL and range helpers
    // ------------------------------------------------------------------

    private static String buildUrl(String spreadsheetId,
                                    String range,
                                    String apiKey) {
        String encodedRange = URLEncoder.encode(range, StandardCharsets.UTF_8);
        StringBuilder url = new StringBuilder()
            .append(VALUES_BASE).append("/").append(spreadsheetId)
            .append("/values/").append(encodedRange)
            .append("?valueRenderOption=UNFORMATTED_VALUE")
            .append("&dateTimeRenderOption=FORMATTED_STRING");
        if (apiKey != null && !apiKey.isEmpty()) {
            url.append("&key=").append(apiKey);
        }
        return url.toString();
    }

    private static String buildSheetQualifier(String sheetName) {
        if (sheetName != null && !sheetName.trim().isEmpty()) {
            return "'" + sheetName.replace("'", "\\'") + "'";
        }
        return "Sheet1";
    }

    // ------------------------------------------------------------------
    // Thread count
    // ------------------------------------------------------------------

    private static int resolveThreadCount(int requested) {
        if (requested > 0) return Math.min(requested, DEFAULT_MAX_THREADS);
        return Math.min(Runtime.getRuntime().availableProcessors(),
                        DEFAULT_MAX_THREADS);
    }

    // ------------------------------------------------------------------
    // Error translation
    // ------------------------------------------------------------------

    private static GsheetsException translateHttpError(int status, String body) {
        String detail = extractErrorDetail(body);
        switch (status) {
            case 400: return new GsheetsException(
                "Bad request (400): " + detail +
                ". Check your sheet ID or range syntax.");
            case 401: return new GsheetsException(
                "Not authorised (401). Run gs4_auth to re-authenticate.");
            case 403: return new GsheetsException(
                "Access denied (403): " + detail +
                ". Ensure the sheet is shared or your account has access.");
            case 404: return new GsheetsException(
                "Sheet not found (404): " + detail +
                ". Check the spreadsheet ID or sheet name.");
            case 429: return new GsheetsException(
                "Rate limit exceeded (429). Wait a moment and try again.");
            default: return new GsheetsException(
                "API error (HTTP " + status + "): " + detail);
        }
    }

    private static String extractErrorDetail(String body) {
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
