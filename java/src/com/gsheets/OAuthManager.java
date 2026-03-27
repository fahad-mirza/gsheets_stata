// =============================================================================
// OAuthManager.java - gsheets package
// Handles OAuth2 browser flow, service account auth, API key passthrough,
// token caching to disk, and automatic token refresh.
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OAuthManager {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    private static final String TOKEN_ENDPOINT =
        "https://oauth2.googleapis.com/token";
    private static final String AUTH_ENDPOINT =
        "https://accounts.google.com/o/oauth2/v2/auth";

    // Scopes needed for Phase 1 (read) and Phase 2 (write).
    // spreadsheets scope covers both read and write.
    // drive.readonly is needed for listing/searching files in Phase 3.
    private static final String SCOPE_SHEETS =
        "https://www.googleapis.com/auth/spreadsheets";
    private static final String SCOPE_SHEETS_RO =
        "https://www.googleapis.com/auth/spreadsheets.readonly";

    // ------------------------------------------------------------------
    // Built-in OAuth2 client -- shipped with the package so users need
    // no Google Cloud setup. Replace these with your production credentials
    // obtained from Google Cloud Console after completing verification.
    // Users can override with their own credentials via gs4_auth options.
    // ------------------------------------------------------------------
    // IMPORTANT: Replace BUILT_IN_CLIENT_ID and BUILT_IN_CLIENT_SECRET
    // with your actual production credentials before publishing to SSC.
    // Leave as empty strings until you have them -- users will be prompted
    // to supply their own via clientid() and clientsecret() options.
    private static final String BUILT_IN_CLIENT_ID     = "";
    private static final String BUILT_IN_CLIENT_SECRET = "";

    // Local redirect port for OAuth2 browser flow.
    // Must be registered in Google Cloud Console as:
    //   http://127.0.0.1:PORT
    private static final int REDIRECT_PORT = 29857;
    private static final String REDIRECT_URI =
        "http://127.0.0.1:" + REDIRECT_PORT;

    // Token cache filename stored in Stata personal ado directory
    private static final String TOKEN_CACHE_FILENAME = "gsheets_token.json";

    // Browser flow timeout - user has 3 minutes to complete consent
    private static final long BROWSER_TIMEOUT_SECONDS = 180;

    // Service account JWT lifetime (Google max is 1 hour)
    private static final long SERVICE_ACCOUNT_TOKEN_LIFETIME = 3600;

    // Refresh access tokens when less than 5 minutes remain
    private static final long REFRESH_BUFFER_SECONDS = 300;

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private final HttpClient httpClient;
    private final Gson gson;
    private final String tokenCachePath;

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    public OAuthManager(String personalAdoDir) {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.tokenCachePath = personalAdoDir + File.separator + TOKEN_CACHE_FILENAME;
    }

    // ==================================================================
    // PUBLIC AUTH ENTRY POINTS
    // ==================================================================

    /**
     * OAuth2 browser flow for a user account.
     * Opens the browser, spins a local server to catch the redirect,
     * exchanges the code for tokens, and caches to disk.
     *
     * @param clientId      OAuth2 client ID from Google Cloud Console
     * @param clientSecret  OAuth2 client secret
     * @param readOnly      if true, requests spreadsheets.readonly scope
     * @return              cached TokenData with access + refresh tokens
     */
    public TokenData authenticateWithBrowser(String clientId,
                                             String clientSecret,
                                             boolean readOnly)
            throws GsheetsException {

        // Use built-in credentials if none supplied and built-in are available
        if ((clientId == null || clientId.trim().isEmpty()) &&
            !BUILT_IN_CLIENT_ID.isEmpty()) {
            clientId     = BUILT_IN_CLIENT_ID;
            clientSecret = BUILT_IN_CLIENT_SECRET;
        }

        if (clientId == null || clientId.trim().isEmpty()) {
            throw new GsheetsException(
                "No OAuth2 credentials available. " +
                "Provide clientid() and clientsecret() options, or " +
                "use gs4_auth, serviceaccount() for automated workflows.");
        }

        String scope = readOnly ? SCOPE_SHEETS_RO : SCOPE_SHEETS;

        // Step 1: Generate state parameter (CSRF protection)
        String state = UUID.randomUUID().toString().replace("-", "");

        // Step 2: Build the Google consent URL
        String authUrl = buildAuthUrl(clientId, scope, state);

        // Step 3: Spin up local redirect server
        AtomicReference<String> codeRef  = new AtomicReference<>();
        AtomicReference<String> stateRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        HttpServer server;
        try {
            server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", REDIRECT_PORT), 0);
        } catch (IOException e) {
            throw new GsheetsException(
                "Could not start local redirect server on port " +
                REDIRECT_PORT + ". Port may be in use. " + e.getMessage());
        }

        server.createContext("/", new OAuthCallbackHandler(
            codeRef, stateRef, errorRef, latch));
        server.setExecutor(null);
        server.start();

        // Step 4: Open browser
        try {
            openBrowser(authUrl);
        } catch (Exception e) {
            server.stop(0);
            throw new GsheetsException(
                "Could not open browser. Please visit this URL manually:\n" +
                authUrl);
        }

        // Step 5: Wait for redirect (user completes consent)
        boolean received;
        try {
            received = latch.await(BROWSER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            server.stop(0);
            Thread.currentThread().interrupt();
            throw new GsheetsException("Auth flow interrupted.");
        } finally {
            server.stop(0);
        }

        if (!received) {
            throw new GsheetsException(
                "Auth flow timed out after " + BROWSER_TIMEOUT_SECONDS +
                " seconds. Run gs4_auth again to retry.");
        }

        // Step 6: Check for error from Google
        if (errorRef.get() != null) {
            throw new GsheetsException(
                "Google returned an error: " + errorRef.get() +
                ". User may have denied access.");
        }

        // Step 7: Validate state (CSRF check)
        if (!state.equals(stateRef.get())) {
            throw new GsheetsException(
                "State mismatch in OAuth2 callback. Possible CSRF attack. " +
                "Run gs4_auth again.");
        }

        String code = codeRef.get();
        if (code == null || code.isEmpty()) {
            throw new GsheetsException(
                "No authorization code received from Google.");
        }

        // Step 8: Exchange code for tokens
        TokenData tokens = exchangeCodeForTokens(
            code, clientId, clientSecret);

        // Step 9: Cache tokens to disk
        TokenCache cache = new TokenCache();
        cache.authType    = "oauth2";
        cache.clientId    = clientId;
        cache.clientSecret = clientSecret;
        cache.accessToken  = tokens.accessToken;
        cache.refreshToken = tokens.refreshToken;
        cache.expiresAt    = tokens.expiresAt;
        cache.scope        = scope;

        saveCache(cache);

        return tokens;
    }

    /**
     * Service account authentication.
     * Signs a JWT with the private key and exchanges for an access token.
     * No browser interaction required.
     *
     * @param serviceAccountJsonPath  path to the service account key JSON file
     * @param readOnly                if true, requests readonly scope
     * @return                        TokenData with access token
     */
    public TokenData authenticateWithServiceAccount(String serviceAccountJsonPath,
                                                    boolean readOnly)
            throws GsheetsException {

        // Read and parse the service account JSON
        ServiceAccountKey key = loadServiceAccountKey(serviceAccountJsonPath);

        String scope = readOnly ? SCOPE_SHEETS_RO : SCOPE_SHEETS;
        long now = Instant.now().getEpochSecond();

        // Build and sign JWT
        String jwt = buildServiceAccountJwt(key, scope, now);

        // Exchange JWT for access token
        String body = "grant_type=" + urlEncode(
                          "urn:ietf:params:oauth:grant-type:jwt-bearer") +
                      "&assertion=" + urlEncode(jwt);

        JsonObject response = postForm(TOKEN_ENDPOINT, body);

        String accessToken = getStringField(response, "access_token",
            "Service account token exchange failed: no access_token in response");
        int expiresIn = response.has("expires_in") ?
            response.get("expires_in").getAsInt() : 3600;

        TokenData tokens = new TokenData();
        tokens.accessToken  = accessToken;
        tokens.refreshToken = null; // service accounts don't use refresh tokens
        tokens.expiresAt    = now + expiresIn;

        // Cache service account tokens (re-minted on refresh via JWT)
        TokenCache cache = new TokenCache();
        cache.authType             = "service_account";
        cache.serviceAccountKeyPath = serviceAccountJsonPath;
        cache.accessToken           = tokens.accessToken;
        cache.refreshToken          = null;
        cache.expiresAt             = tokens.expiresAt;
        cache.scope                 = scope;

        saveCache(cache);

        return tokens;
    }

    /**
     * API key mode for public sheets only.
     * No token exchange needed — just caches the key for use in requests.
     *
     * @param apiKey  Google API key from Cloud Console
     */
    public void authenticateWithApiKey(String apiKey) throws GsheetsException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new GsheetsException("API key cannot be empty.");
        }

        TokenCache cache = new TokenCache();
        cache.authType    = "api_key";
        cache.apiKey      = apiKey.trim();
        cache.accessToken  = null;
        cache.refreshToken = null;
        cache.expiresAt    = Long.MAX_VALUE; // API keys don't expire

        saveCache(cache);
    }

    /**
     * Clears cached credentials from disk.
     */
    public void deauth() throws GsheetsException {
        File f = new File(tokenCachePath);
        if (f.exists()) {
            if (!f.delete()) {
                throw new GsheetsException(
                    "Could not delete token cache at: " + tokenCachePath);
            }
        }
        // Silently succeeds even if no cache exists
    }

    // ==================================================================
    // TOKEN RETRIEVAL (used by other Java classes before API calls)
    // ==================================================================

    /**
     * Returns a valid access token, refreshing automatically if expired.
     * This is the main entry point called by SheetReader and other API classes.
     *
     * @return valid access token string, or null if in API key mode
     */
    public String getValidAccessToken() throws GsheetsException {
        TokenCache cache = loadCache();

        if (cache == null) {
            throw new GsheetsException(
                "Not authenticated. Run gs4_auth first.");
        }

        switch (cache.authType) {
            case "api_key":
                return null; // API key is passed separately

            case "oauth2":
                return getValidOAuth2Token(cache);

            case "service_account":
                return getValidServiceAccountToken(cache);

            default:
                throw new GsheetsException(
                    "Unknown auth type in token cache: " + cache.authType +
                    ". Run gs4_auth again.");
        }
    }

    /**
     * Returns the API key if in api_key mode, otherwise null.
     */
    public String getApiKey() throws GsheetsException {
        TokenCache cache = loadCache();
        if (cache == null) return null;
        return "api_key".equals(cache.authType) ? cache.apiKey : null;
    }

    /**
     * Returns the current auth type, or null if not authenticated.
     */
    public String getAuthType() throws GsheetsException {
        TokenCache cache = loadCache();
        return cache != null ? cache.authType : null;
    }

    /**
     * Returns true if a valid token cache exists.
     */
    public boolean isAuthenticated() {
        try {
            TokenCache cache = loadCache();
            return cache != null;
        } catch (GsheetsException e) {
            return false;
        }
    }

    // ==================================================================
    // PRIVATE: TOKEN REFRESH
    // ==================================================================

    private String getValidOAuth2Token(TokenCache cache) throws GsheetsException {
        long now = Instant.now().getEpochSecond();

        // Token still valid — return as-is
        if (cache.expiresAt - now > REFRESH_BUFFER_SECONDS) {
            return cache.accessToken;
        }

        // Token expired or near expiry — refresh it
        if (cache.refreshToken == null || cache.refreshToken.isEmpty()) {
            throw new GsheetsException(
                "OAuth2 access token expired and no refresh token available. " +
                "Run gs4_auth again to re-authenticate.");
        }

        String body = "grant_type=refresh_token" +
                      "&refresh_token=" + urlEncode(cache.refreshToken) +
                      "&client_id="     + urlEncode(cache.clientId) +
                      "&client_secret=" + urlEncode(cache.clientSecret);

        JsonObject response;
        try {
            response = postForm(TOKEN_ENDPOINT, body);
        } catch (GsheetsException e) {
            throw new GsheetsException(
                "Token refresh failed: " + e.getMessage() +
                ". Run gs4_auth again to re-authenticate.");
        }

        String newAccessToken = getStringField(response, "access_token",
            "Token refresh response missing access_token");
        int expiresIn = response.has("expires_in") ?
            response.get("expires_in").getAsInt() : 3600;

        // Update cache with new access token (refresh token stays the same)
        cache.accessToken = newAccessToken;
        cache.expiresAt   = now + expiresIn;
        saveCache(cache);

        return newAccessToken;
    }

    private String getValidServiceAccountToken(TokenCache cache) throws GsheetsException {
        long now = Instant.now().getEpochSecond();

        // Token still valid
        if (cache.expiresAt - now > REFRESH_BUFFER_SECONDS) {
            return cache.accessToken;
        }

        // Re-mint a new JWT and exchange for a fresh access token
        ServiceAccountKey key = loadServiceAccountKey(cache.serviceAccountKeyPath);
        String jwt = buildServiceAccountJwt(key, cache.scope, now);

        String body = "grant_type=" + urlEncode(
                          "urn:ietf:params:oauth:grant-type:jwt-bearer") +
                      "&assertion=" + urlEncode(jwt);

        JsonObject response = postForm(TOKEN_ENDPOINT, body);

        String newAccessToken = getStringField(response, "access_token",
            "Service account token refresh failed: no access_token");
        int expiresIn = response.has("expires_in") ?
            response.get("expires_in").getAsInt() : 3600;

        cache.accessToken = newAccessToken;
        cache.expiresAt   = now + expiresIn;
        saveCache(cache);

        return newAccessToken;
    }

    // ==================================================================
    // PRIVATE: TOKEN EXCHANGE
    // ==================================================================

    private TokenData exchangeCodeForTokens(String code,
                                             String clientId,
                                             String clientSecret)
            throws GsheetsException {

        String body = "grant_type=authorization_code" +
                      "&code="          + urlEncode(code) +
                      "&redirect_uri="  + urlEncode(REDIRECT_URI) +
                      "&client_id="     + urlEncode(clientId) +
                      "&client_secret=" + urlEncode(clientSecret);

        JsonObject response = postForm(TOKEN_ENDPOINT, body);

        String accessToken = getStringField(response, "access_token",
            "Token exchange failed: no access_token in response");
        String refreshToken = response.has("refresh_token") ?
            response.get("refresh_token").getAsString() : null;
        int expiresIn = response.has("expires_in") ?
            response.get("expires_in").getAsInt() : 3600;

        TokenData tokens = new TokenData();
        tokens.accessToken  = accessToken;
        tokens.refreshToken = refreshToken;
        tokens.expiresAt    = Instant.now().getEpochSecond() + expiresIn;

        return tokens;
    }

    // ==================================================================
    // PRIVATE: SERVICE ACCOUNT JWT
    // ==================================================================

    private String buildServiceAccountJwt(ServiceAccountKey key,
                                           String scope,
                                           long now)
            throws GsheetsException {

        // JWT header
        String header = base64UrlEncode(
            "{\"alg\":\"RS256\",\"typ\":\"JWT\"}");

        // JWT claim set
        String claims = base64UrlEncode(
            "{\"iss\":\"" + key.clientEmail + "\"," +
             "\"scope\":\"" + scope + "\"," +
             "\"aud\":\"" + TOKEN_ENDPOINT + "\"," +
             "\"exp\":" + (now + SERVICE_ACCOUNT_TOKEN_LIFETIME) + "," +
             "\"iat\":" + now + "}");

        String signingInput = header + "." + claims;

        // Sign with RS256 using the private key
        byte[] signature;
        try {
            byte[] privateKeyBytes = decodePrivateKey(key.privateKey);
            java.security.spec.PKCS8EncodedKeySpec keySpec =
                new java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes);
            java.security.KeyFactory kf =
                java.security.KeyFactory.getInstance("RSA");
            java.security.PrivateKey pk = kf.generatePrivate(keySpec);

            java.security.Signature signer =
                java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(pk);
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            signature = signer.sign();
        } catch (Exception e) {
            throw new GsheetsException(
                "Failed to sign service account JWT: " + e.getMessage() +
                ". Verify your service account key file is valid.");
        }

        return signingInput + "." + base64UrlEncode(signature);
    }

    // ==================================================================
    // PRIVATE: URL + AUTH HELPERS
    // ==================================================================

    private String buildAuthUrl(String clientId, String scope, String state) {
        return AUTH_ENDPOINT +
               "?response_type=code" +
               "&client_id="     + urlEncode(clientId) +
               "&redirect_uri="  + urlEncode(REDIRECT_URI) +
               "&scope="         + urlEncode(scope) +
               "&state="         + state +
               "&access_type=offline" +
               "&prompt=consent";  // force consent to always get refresh_token
    }

    private void openBrowser(String url) throws Exception {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(new URI(url));
        } else {
            // Fallback for environments where Desktop is not supported
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + url);
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec("open " + url);
            } else {
                Runtime.getRuntime().exec("xdg-open " + url);
            }
        }
    }

    // ==================================================================
    // PRIVATE: HTTP POST (form-encoded)
    // ==================================================================

    private JsonObject postForm(String url, String formBody) throws GsheetsException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new GsheetsException(
                "HTTP request to " + url + " failed: " + e.getMessage());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody = response.body();
            String errorMsg = extractErrorMessage(errorBody);
            throw new GsheetsException(
                "Token endpoint returned HTTP " + response.statusCode() +
                ": " + errorMsg);
        }

        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new GsheetsException(
                "Could not parse token response as JSON: " + response.body());
        }
    }

    // ==================================================================
    // PRIVATE: TOKEN CACHE (disk I/O)
    // ==================================================================

    private void saveCache(TokenCache cache) throws GsheetsException {
        try {
            // Ensure parent directory exists
            File f = new File(tokenCachePath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(f)) {
                gson.toJson(cache, writer);
            }
        } catch (IOException e) {
            throw new GsheetsException(
                "Could not save token cache to: " + tokenCachePath +
                " -- " + e.getMessage());
        }
    }

    private TokenCache loadCache() throws GsheetsException {
        File f = new File(tokenCachePath);
        if (!f.exists()) return null;

        try (FileReader reader = new FileReader(f)) {
            return gson.fromJson(reader, TokenCache.class);
        } catch (IOException e) {
            throw new GsheetsException(
                "Could not read token cache from: " + tokenCachePath +
                " -- " + e.getMessage());
        } catch (Exception e) {
            throw new GsheetsException(
                "Token cache is corrupt or unreadable at: " + tokenCachePath +
                ". Run gs4_auth again to re-authenticate.");
        }
    }

    // ==================================================================
    // PRIVATE: SERVICE ACCOUNT KEY LOADING
    // ==================================================================

    private ServiceAccountKey loadServiceAccountKey(String path) throws GsheetsException {
        if (path == null || path.trim().isEmpty()) {
            throw new GsheetsException("Service account key path is empty.");
        }
        File f = new File(path);
        if (!f.exists()) {
            throw new GsheetsException(
                "Service account key file not found: " + path);
        }
        try (FileReader reader = new FileReader(f)) {
            ServiceAccountKey key = gson.fromJson(reader, ServiceAccountKey.class);
            if (key.clientEmail == null || key.privateKey == null) {
                throw new GsheetsException(
                    "Service account key file is missing client_email or private_key: " +
                    path);
            }
            return key;
        } catch (IOException e) {
            throw new GsheetsException(
                "Could not read service account key file: " + e.getMessage());
        }
    }

    // ==================================================================
    // PRIVATE: ENCODING UTILITIES
    // ==================================================================

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String base64UrlEncode(String input) {
        return base64UrlEncode(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlEncode(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }

    private static byte[] decodePrivateKey(String pemKey) {
        // Strip PEM header/footer and whitespace
        String stripped = pemKey
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(stripped);
    }

    private static String getStringField(JsonObject obj, String field, String errorMsg)
            throws GsheetsException {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            throw new GsheetsException(errorMsg);
        }
        return obj.get(field).getAsString();
    }

    private static String extractErrorMessage(String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("error_description")) {
                return obj.get("error_description").getAsString();
            }
            if (obj.has("error")) {
                return obj.get("error").getAsString();
            }
        } catch (Exception ignored) { }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    // ==================================================================
    // INNER CLASS: OAuth2 callback HTTP handler
    // ==================================================================

    private static class OAuthCallbackHandler implements HttpHandler {
        private final AtomicReference<String> codeRef;
        private final AtomicReference<String> stateRef;
        private final AtomicReference<String> errorRef;
        private final CountDownLatch latch;

        OAuthCallbackHandler(AtomicReference<String> codeRef,
                              AtomicReference<String> stateRef,
                              AtomicReference<String> errorRef,
                              CountDownLatch latch) {
            this.codeRef  = codeRef;
            this.stateRef = stateRef;
            this.errorRef = errorRef;
            this.latch    = latch;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);

            codeRef.set(params.get("code"));
            stateRef.set(params.get("state"));
            errorRef.set(params.get("error"));

            // Return a clean success page to the browser
            String html = buildSuccessPage(params.containsKey("error"));
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }

            latch.countDown();
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> result = new HashMap<>();
            if (query == null || query.isEmpty()) return result;
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String key = URLDecoder.decode(
                        pair.substring(0, idx), StandardCharsets.UTF_8);
                    String val = URLDecoder.decode(
                        pair.substring(idx + 1), StandardCharsets.UTF_8);
                    result.put(key, val);
                }
            }
            return result;
        }

        private String buildSuccessPage(boolean isError) {
            if (isError) {
                return "<!DOCTYPE html><html><head><title>gsheets - Auth Failed</title>" +
                       "<style>body{font-family:Arial,sans-serif;text-align:center;" +
                       "padding:60px;background:#fff5f5;}" +
                       "h1{color:#c0392b;}p{color:#555;font-size:16px;}</style></head>" +
                       "<body><h1>Authentication Failed</h1>" +
                       "<p>Access was denied or an error occurred.</p>" +
                       "<p>Return to Stata and run <code>gs4_auth</code> again.</p>" +
                       "</body></html>";
            }
            return "<!DOCTYPE html><html><head><title>gsheets - Authenticated</title>" +
                   "<style>body{font-family:Arial,sans-serif;text-align:center;" +
                   "padding:60px;background:#f0fff4;}" +
                   "h1{color:#1a7f3c;}p{color:#555;font-size:16px;}" +
                   ".check{font-size:72px;}</style></head>" +
                   "<body><div class=\"check\">&#10003;</div>" +
                   "<h1>Successfully authenticated!</h1>" +
                   "<p>You can close this browser tab and return to Stata.</p>" +
                   "</body></html>";
        }
    }

    // ==================================================================
    // INNER DATA CLASSES
    // ==================================================================

    /** Returned to Stata layer after successful authentication. */
    public static class TokenData {
        public String accessToken;
        public String refreshToken;
        public long   expiresAt;    // Unix timestamp
    }

    /** Persisted to disk as gsheets_token.json */
    private static class TokenCache {
        String authType;             // "oauth2" | "service_account" | "api_key"
        String clientId;             // oauth2 only
        String clientSecret;         // oauth2 only
        String accessToken;
        String refreshToken;         // oauth2 only
        long   expiresAt;
        String scope;
        String apiKey;               // api_key only
        String serviceAccountKeyPath; // service_account only
    }

    /** Maps fields from Google service account JSON key file */
    private static class ServiceAccountKey {
        String type;
        String clientEmail;  // maps from "client_email"
        String privateKey;   // maps from "private_key"
        String tokenUri;     // maps from "token_uri"

        // Gson needs field names matching the JSON keys exactly.
        // Google uses snake_case so we declare the fields to match.
        // This class uses a custom deserializer - see fromJson below.
    }
}
