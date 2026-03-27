*! gs4_auth.ado - gsheets package
*! Authenticate with Google Sheets API
*! Author: Fahad Mirza
*! Version: 1.0.0

capture program drop gs4_auth
program define gs4_auth, rclass

    version 17

    syntax [, ///
        clientid(string)       ///  OAuth2 client ID
        clientsecret(string)   ///  OAuth2 client secret
        serviceaccount(string) ///  path to service account key JSON
        apikey(string)         ///  Google API key (public sheets only)
        readonly               ///  request read-only scope
        status                 ///  show current auth status
        ]

    // ------------------------------------------------------------------
    // Verify gsheets.jar is findable on adopath
    // javacall expects just the filename -- Stata finds it on adopath
    // ------------------------------------------------------------------
    local jarname "gsheets.jar"

    // Build personal dir path for token cache
    // Safe construction: normalise slashes, strip trailing separator
    local personal : sysdir PERSONAL
    local personal = subinstr("`personal'", "/", "\", .)
    if substr("`personal'", -1, 1) == "\" {
        local personal = substr("`personal'", 1, length("`personal'") - 1)
    }
    local personaldir "`personal'\"

    // Verify the jar actually exists somewhere on adopath before calling
    local jarfound 0
    foreach dir in "`personal'" "C:\ado\personal" "`c(sysdir_plus)'" {
        local testpath = subinstr("`dir'", "/", "\", .)
        if substr("`testpath'", -1, 1) == "\" {
            local testpath = substr("`testpath'", 1, length("`testpath'") - 1)
        }
        if fileexists("`testpath'\\`jarname'") {
            local jarfound 1
        }
    }
    if `jarfound' == 0 {
        display as error "`jarname' not found on adopath."
        display as error "Please run build.bat to compile and install gsheets."
        exit 601
    }

    // ------------------------------------------------------------------
    // Determine action
    // ------------------------------------------------------------------
    local action ""
    local readonly_flag "0"

    if "`status'" != "" {
        local action "status"
    }
    else if `"`serviceaccount'"' != "" {
        local action "serviceaccount"
    }
    else if `"`apikey'"' != "" {
        local action "apikey"
    }
    else {
        local action "browser"
    }

    if "`readonly'" != "" {
        local readonly_flag "1"
    }

    // ------------------------------------------------------------------
    // Validate required args per action
    // If no clientid/clientsecret supplied, OAuthManager uses built-in
    // package credentials (if configured) -- no error needed here.
    // The Java layer will raise an informative error if neither built-in
    // nor user-supplied credentials are available.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Call Java -- pass bare jar filename, Stata finds it on adopath
    // ------------------------------------------------------------------
    javacall com.gsheets.GsheetsAuth execute, jars(`"`jarname'"') args( ///
        "`action'"             ///  args[0]: action
        `"`personaldir'"'      ///  args[1]: personal ado dir for token cache
        `"`clientid'"'         ///  args[2]: client ID (browser only)
        `"`clientsecret'"'     ///  args[3]: client secret (browser only)
        `"`serviceaccount'"'   ///  args[4]: service account key path
        `"`apikey'"'           ///  args[5]: API key
        "`readonly_flag'"      ///  args[6]: "1" = readonly scope
    )

    // ------------------------------------------------------------------
    // Handle result
    // ------------------------------------------------------------------
    if `"`gs_auth_status'"' == "error" {
        display as error `"`gs_auth_message'"'
        exit 499
    }

    return local auth_type  `"`gs_auth_type'"'
    return local message    `"`gs_auth_message'"'
    if `"`gs_expires_at'"' != "" {
        return local expires_at `"`gs_expires_at'"'
    }

end
