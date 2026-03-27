*! import_gsheet.ado - gsheets package
*! Import data from a Google Sheet into Stata
*! Author: Fahad Mirza
*! Version: 1.0.0

capture program drop import_gsheet
program define import_gsheet, rclass

    version 17

    syntax anything(name=sheetid) [,   ///
        sheet(string)                  ///  sheet/tab name
        range(string)                  ///  A1-notation range e.g. "A1:E100"
        FIRSTrow                       ///  first row = variable names
        clear                          ///  clear data in memory before import
        cores(integer 0)               ///  parallel threads (0 = auto)
        verbose                        ///  show progress
        ]

    // ------------------------------------------------------------------
    // Strip surrounding quotes from sheet ID / URL
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
    // Parse flags
    // ------------------------------------------------------------------
    local firstrowflag "0"
    if "`firstrow'" != "" local firstrowflag "1"

    local verboseflag "0"
    if "`verbose'" != "" local verboseflag "1"

    // ------------------------------------------------------------------
    // Clear check -- mirrors import excel behaviour
    // ------------------------------------------------------------------
    if `"`clear'"' != "" {
        quietly clear
    }
    else {
        // Abort if data in memory would be lost (r(4) = Stata convention)
        if _N > 0 | c(k) > 0 {
            display as error "no; data in memory would be lost"
            display as error "add option clear to discard current data"
            exit 4
        }
    }

    // ------------------------------------------------------------------
    // Call Java
    // ------------------------------------------------------------------
    javacall com.gsheets.GsheetsImport execute, jars("gsheets.jar") args( ///
        `"`sheetid'"'          ///  args[0]: spreadsheet ID or URL
        `"`personaldir'"'      ///  args[1]: personal ado dir for token cache
        `"`sheet'"'            ///  args[2]: sheet/tab name (empty = first sheet)
        `"`range'"'            ///  args[3]: A1 range (empty = entire sheet)
        "`firstrowflag'"       ///  args[4]: "1" = first row is var names
        "`cores'"              ///  args[5]: number of threads
        "`verboseflag'"        ///  args[6]: verbose flag
    )

    // ------------------------------------------------------------------
    // Handle result
    // ------------------------------------------------------------------
    if `"`gs_status'"' == "error" {
        display as error `"`gs_error_msg'"'
        exit 499
    }

    // Surface results in r()
    return local nobs  `"`gs_nobs'"'
    return local nvars `"`gs_nvars'"'

end
