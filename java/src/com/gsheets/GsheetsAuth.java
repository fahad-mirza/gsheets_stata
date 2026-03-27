// =============================================================================
// GsheetsAuth.java - gsheets package
// SFI entry point for all authentication commands.
// Called by gs4_auth.ado and gs4_deauth.ado via javacall.
//
// Args layout (0-indexed):
//   0  action       "browser" | "serviceaccount" | "apikey" | "deauth" | "status"
//   1  personalDir  c(sysdir_personal) from Stata - where token cache is stored
//   2  clientId     OAuth2 client ID        (browser mode only)
//   3  clientSecret OAuth2 client secret    (browser mode only)
//   4  keyPath      service account key path (serviceaccount mode only)
//   5  apiKey       API key string           (apikey mode only)
//   6  readOnly     "1" = readonly scope, "0" = read-write scope
//
// Return macros set via SFI (readable in Stata as r()):
//   gs_auth_status    "ok" or "error"
//   gs_auth_message   human-readable result or error message
//   gs_auth_type      auth type stored ("oauth2"|"service_account"|"api_key")
//   gs_expires_at     Unix timestamp of token expiry (oauth2/service_account)
//
// Author: Fahad Mirza
// =============================================================================

package com.gsheets;

import com.stata.sfi.Macro;
import com.stata.sfi.SFIToolkit;

public class GsheetsAuth {

    // Macro names written back to Stata
    private static final String MACRO_STATUS     = "gs_auth_status";
    private static final String MACRO_MESSAGE    = "gs_auth_message";
    private static final String MACRO_AUTH_TYPE  = "gs_auth_type";
    private static final String MACRO_EXPIRES_AT = "gs_expires_at";

    // ------------------------------------------------------------------
    // SFI entry point - called by javacall
    // ------------------------------------------------------------------

    public static int execute(String[] args) {

        if (args == null || args.length < 2) {
            setError("Internal error: GsheetsAuth called with too few arguments.");
            return 1;
        }

        String action      = args[0].trim().toLowerCase();
        String personalDir = args[1].trim();

        OAuthManager auth = new OAuthManager(personalDir);

        try {
            switch (action) {
                case "browser":       return doBrowserAuth(auth, args);
                case "serviceaccount": return doServiceAccountAuth(auth, args);
                case "apikey":        return doApiKeyAuth(auth, args);
                case "deauth":        return doDeauth(auth);
                case "status":        return doStatus(auth);
                default:
                    setError("Unknown auth action: '" + action +
                             "'. Expected: browser, serviceaccount, apikey, deauth, status.");
                    return 1;
            }
        } catch (GsheetsException e) {
            setError(e.getMessage());
            return 1;
        } catch (Exception e) {
            setError("Unexpected error in GsheetsAuth: " + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------
    // Action handlers
    // ------------------------------------------------------------------

    private static int doBrowserAuth(OAuthManager auth, String[] args)
            throws GsheetsException {

        if (args.length < 5) {
            throw new GsheetsException(
                "Browser auth requires client_id and client_secret. " +
                "Run: gs4_auth, clientid(\"...\") clientsecret(\"...\")");
        }

        String clientId     = args[2].trim();
        String clientSecret = args[3].trim();
        boolean readOnly    = args.length > 6 && "1".equals(args[6].trim());

        if (clientId.isEmpty()) {
            throw new GsheetsException(
                "client_id is empty. Provide your OAuth2 client ID from " +
                "Google Cloud Console.");
        }
        if (clientSecret.isEmpty()) {
            throw new GsheetsException(
                "client_secret is empty. Provide your OAuth2 client secret " +
                "from Google Cloud Console.");
        }

        SFIToolkit.displayln("{txt}Opening browser for Google authentication...");
        SFIToolkit.displayln("{txt}Waiting for you to complete the sign-in...");

        OAuthManager.TokenData tokens = auth.authenticateWithBrowser(
            clientId, clientSecret, readOnly);

        Macro.setLocal(MACRO_STATUS,     "ok");
        Macro.setLocal(MACRO_MESSAGE,    "Authenticated successfully via browser. " +
                                          "Token cached to disk.");
        Macro.setLocal(MACRO_AUTH_TYPE,  "oauth2");
        Macro.setLocal(MACRO_EXPIRES_AT, String.valueOf(tokens.expiresAt));

        SFIToolkit.displayln("{txt}Authentication successful. Credentials cached.");
        return 0;
    }

    private static int doServiceAccountAuth(OAuthManager auth, String[] args)
            throws GsheetsException {

        if (args.length < 5) {
            throw new GsheetsException(
                "Service account auth requires a key file path. " +
                "Run: gs4_auth, serviceaccount(\"path/to/key.json\")");
        }

        String keyPath   = args[4].trim();
        boolean readOnly = args.length > 6 && "1".equals(args[6].trim());

        if (keyPath.isEmpty()) {
            throw new GsheetsException("Service account key path is empty.");
        }

        SFIToolkit.displayln("{txt}Authenticating with service account...");

        OAuthManager.TokenData tokens = auth.authenticateWithServiceAccount(
            keyPath, readOnly);

        Macro.setLocal(MACRO_STATUS,     "ok");
        Macro.setLocal(MACRO_MESSAGE,    "Service account authenticated successfully.");
        Macro.setLocal(MACRO_AUTH_TYPE,  "service_account");
        Macro.setLocal(MACRO_EXPIRES_AT, String.valueOf(tokens.expiresAt));

        SFIToolkit.displayln("{txt}Service account authenticated. Token cached.");
        return 0;
    }

    private static int doApiKeyAuth(OAuthManager auth, String[] args)
            throws GsheetsException {

        if (args.length < 6) {
            throw new GsheetsException(
                "API key auth requires an API key. " +
                "Run: gs4_auth, apikey(\"AIza...\")");
        }

        String apiKey = args[5].trim();

        if (apiKey.isEmpty()) {
            throw new GsheetsException("API key is empty.");
        }

        auth.authenticateWithApiKey(apiKey);

        Macro.setLocal(MACRO_STATUS,     "ok");
        Macro.setLocal(MACRO_MESSAGE,    "API key saved. " +
                                          "Note: API key mode only works with " +
                                          "publicly shared sheets.");
        Macro.setLocal(MACRO_AUTH_TYPE,  "api_key");
        Macro.setLocal(MACRO_EXPIRES_AT, "");

        SFIToolkit.displayln("{txt}API key saved.");
        SFIToolkit.displayln("{txt}Note: API key mode only works with publicly shared sheets.");
        return 0;
    }

    private static int doDeauth(OAuthManager auth) throws GsheetsException {
        auth.deauth();

        Macro.setLocal(MACRO_STATUS,    "ok");
        Macro.setLocal(MACRO_MESSAGE,   "Credentials cleared.");
        Macro.setLocal(MACRO_AUTH_TYPE, "");

        SFIToolkit.displayln("{txt}gsheets credentials cleared.");
        return 0;
    }

    private static int doStatus(OAuthManager auth) throws GsheetsException {
        String authType = auth.getAuthType();

        if (authType == null) {
            Macro.setLocal(MACRO_STATUS,    "ok");
            Macro.setLocal(MACRO_MESSAGE,   "Not authenticated.");
            Macro.setLocal(MACRO_AUTH_TYPE, "none");
            SFIToolkit.displayln("{txt}gsheets: not authenticated. Run gs4_auth.");
        } else {
            Macro.setLocal(MACRO_STATUS,    "ok");
            Macro.setLocal(MACRO_MESSAGE,   "Authenticated as: " + authType);
            Macro.setLocal(MACRO_AUTH_TYPE, authType);
            SFIToolkit.displayln("{txt}gsheets: authenticated (" + authType + ")");
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private static void setError(String message) {
        Macro.setLocal(MACRO_STATUS,  "error");
        Macro.setLocal(MACRO_MESSAGE, message);
        SFIToolkit.error(message);
    }
}
