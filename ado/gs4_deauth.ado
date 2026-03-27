*! gs4_deauth.ado - gsheets package
*! Clear cached Google Sheets credentials
*! Author: Fahad Mirza
*! Version: 1.0.0

capture program drop gs4_deauth
program define gs4_deauth

    syntax

    // ------------------------------------------------------------------
    // Build personal dir path for token cache
    // ------------------------------------------------------------------
    local personal : sysdir PERSONAL
    local personal = subinstr("`personal'", "/", "\", .)
    if substr("`personal'", -1, 1) == "\" {
        local personal = substr("`personal'", 1, length("`personal'") - 1)
    }
    local personaldir "`personal'\"

    // ------------------------------------------------------------------
    // Call Java -- bare jar filename, Stata finds it on adopath
    // ------------------------------------------------------------------
    javacall com.gsheets.GsheetsAuth execute, jars("gsheets.jar") args( ///
        "deauth"               ///  args[0]: action
        `"`personaldir'"'      ///  args[1]: personal ado dir
        ""                     ///  args[2]: unused
        ""                     ///  args[3]: unused
        ""                     ///  args[4]: unused
        ""                     ///  args[5]: unused
        "0"                    ///  args[6]: unused
    )

    // ------------------------------------------------------------------
    // Handle result
    // ------------------------------------------------------------------
    if `"`gs_auth_status'"' == "error" {
        display as error `"`gs_auth_message'"'
        exit 499
    }

end
