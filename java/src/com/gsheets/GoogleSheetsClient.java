// =============================================================================
// GoogleSheetsClient.java - gsheets package
// Shared HTTP client used by all API classes (gs4_get, SheetReader, etc).
// Handles authorisation headers, error translation, and rate-limit backoff.
// One static instance shared across the entire session.
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class GoogleSheetsClient {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    private static final String SHEETS_BASE =
        "https://sheets.googleapis.com/v4/spreadsheets";

    // Rate-limit backoff: wait up to 3 attempts before failing
    private static final int    MAX_RETRIES        = 3;
    private static final long   BASE_BACKOFF_MS    = 2000;   // 2 seconds
    private static final int    REQUEST_TIMEOUT_S  = 30;

    // ------------------------------------------------------------------
    // Shared HttpClient -- one instance for the whole session
    // ------------------------------------------------------------------

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * GET request to the Sheets API.
     * Automatically attaches the Authorization header (or api_key param).
     * Retries on 429 RESOURCE_EXHAUSTED with exponential backoff.
     *
     * @param path         path after /v4/spreadsheets, e.g. "/{id}?fields=..."
     * @param accessToken  OAuth2/service-account token, or null for API key mode
     * @param apiKey       API key, or null for token mode
     * @return             parsed JsonObject of the response body
     */
    public static JsonObject get(String path,
                                  String accessToken,
                                  String apiKey)
            throws GsheetsException {

        String url = SHEETS_BASE + path;

        // Append api_key query param if in API key mode
        if (apiKey != null && !apiKey.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + "key=" + apiKey;
        }

        int attempt = 0;
        while (true) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                .header("Accept", "application/json");

            // Attach token if available
            if (accessToken != null && !accessToken.isEmpty()) {
                builder.header("Authorization", "Bearer " + accessToken);
            }

            HttpResponse<String> response;
            try {
                response = HTTP_CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException e) {
                throw new GsheetsException(
                    "Network error contacting Google Sheets API: " +
                    e.getMessage());
            }

            int status = response.statusCode();

            // Success
            if (status >= 200 && status < 300) {
                return parseJson(response.body());
            }

            // Rate limit -- back off and retry
            if (status == 429 && attempt < MAX_RETRIES) {
                long waitMs = BASE_BACKOFF_MS * (long) Math.pow(2, attempt);
                attempt++;
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new GsheetsException("Request interrupted during backoff.");
                }
                continue;
            }

            // All other errors -- translate to a clean message
            throw translateError(status, response.body());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JsonObject parseJson(String body) throws GsheetsException {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new GsheetsException(
                "Could not parse API response as JSON. " +
                "Response was: " + truncate(body, 200));
        }
    }

    private static GsheetsException translateError(int status, String body) {
        String detail = extractErrorMessage(body);
        switch (status) {
            case 400: return new GsheetsException(
                "Bad request (400): " + detail +
                ". Check your sheet ID or range.");
            case 401: return new GsheetsException(
                "Not authorised (401): " + detail +
                ". Run gs4_auth to re-authenticate.");
            case 403: return new GsheetsException(
                "Access denied (403): " + detail +
                ". Ensure the sheet is shared with your account or " +
                "your service account has access.");
            case 404: return new GsheetsException(
                "Sheet not found (404): " + detail +
                ". Check the spreadsheet ID or URL.");
            case 429: return new GsheetsException(
                "Rate limit exceeded (429): " + detail +
                ". Too many requests. Wait a moment and try again.");
            case 500: return new GsheetsException(
                "Google server error (500): " + detail +
                ". Try again in a moment.");
            case 503: return new GsheetsException(
                "Google service unavailable (503): " + detail +
                ". Try again in a moment.");
            default:  return new GsheetsException(
                "API error (HTTP " + status + "): " + detail);
        }
    }

    private static String extractErrorMessage(String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("error")) {
                JsonObject err = obj.getAsJsonObject("error");
                if (err.has("message")) {
                    return err.get("message").getAsString();
                }
                if (err.has("status")) {
                    return err.get("status").getAsString();
                }
            }
        } catch (Exception ignored) { }
        return truncate(body, 150);
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
