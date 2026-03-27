*! gs4_get.ado - gsheets package
*! Get metadata about a Google Spreadsheet
*! Author: Fahad Mirza
*! Version: 1.0.0

capture program drop gs4_get
program define gs4_get, rclass

    version 17

    syntax anything(name=sheetid) [, verbose]

    // ------------------------------------------------------------------
    // Strip any surrounding quotes from the sheet ID / URL
    // (compound quoting can leave stray " characters)
    // ------------------------------------------------------------------
    local sheetid = strtrim(`"`sheetid'"')
    local sheetid = subinstr(`"`sheetid'"', `"""', "", .)

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
    // Verbose flag
    // ------------------------------------------------------------------
    local verboseflag "0"
    if "`verbose'" != "" local verboseflag "1"

    // ------------------------------------------------------------------
    // Call Java
    // ------------------------------------------------------------------
    javacall com.gsheets.GsheetsGet execute, jars("gsheets.jar") args( ///
        `"`sheetid'"'          ///  args[0]: spreadsheet ID or URL
        `"`personaldir'"'      ///  args[1]: personal ado dir for token cache
        "`verboseflag'"        ///  args[2]: verbose flag
    )

    // ------------------------------------------------------------------
    // Handle result
    // ------------------------------------------------------------------
    if `"`gs_status'"' == "error" {
        display as error `"`gs_error_msg'"'
        exit 499
    }

    // Surface results in r()
    return local title       `"`gs_title'"'
    return local id          `"`gs_id'"'
    return local nsheets     `"`gs_nsheets'"'
    return local sheet_names `"`gs_sheet_names'"'

end
