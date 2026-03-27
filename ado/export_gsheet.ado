*! export_gsheet.ado - gsheets package
*! Export Stata dataset to a Google Sheet
*! Author: Fahad Mirza
*! Version: 1.0.0

capture program drop export_gsheet
program define export_gsheet, rclass

    version 17

    syntax [if] [in] using/ [,      ///
        sheet(string)               ///  target sheet/tab name
        replace                     ///  overwrite existing sheet content
        append                      ///  append rows after last populated row
        FIRSTrow(string)            ///  "variables" | "varlabels" | "none"
        NOLabel                     ///  suppress header row (same as firstrow(none))
        Generate(string)            ///  append only: name of datetime column to add
        verbose                     ///  show progress
        ]

    // ------------------------------------------------------------------
    // Strip surrounding quotes from sheet ID / URL
    // ------------------------------------------------------------------
    local using = strtrim(`"`using'"')
    local using = subinstr(`"`using'"', `"""', "", .)

    // ------------------------------------------------------------------
    // Validate write mode
    // ------------------------------------------------------------------
    if "`replace'" == "" & "`append'" == "" {
        display as error "specify either replace or append."
        display as text  "  replace: clear existing content and write from scratch"
        display as text  "  append:  add rows after the last populated row"
        exit 198
    }
    if "`replace'" != "" & "`append'" != "" {
        display as error "specify either replace or append, not both."
        exit 198
    }

    local writemode "replace"
    if "`append'" != "" local writemode "append"

    // ------------------------------------------------------------------
    // Validate generate() option -- only meaningful with append
    // ------------------------------------------------------------------
    if `"`generate'"' != "" & "`writemode'" == "replace" {
        display as error "generate() can only be used with append, not replace."
        exit 198
    }
    if `"`generate'"' != "" {
        // Validate as a legal Stata variable name
        capture confirm name `generate'
        if _rc {
            display as error `"generate(`generate') is not a valid Stata variable name."'
            exit 198
        }
    }

    // ------------------------------------------------------------------
    // Default sheet name
    // ------------------------------------------------------------------
    if `"`sheet'"' == "" local sheet "Sheet1"

    // ------------------------------------------------------------------
    // Parse firstrow option
    // Append mode defaults to none (no header) -- avoids accidental
    // duplicate headers. Replace mode defaults to variables.
    // User can override either default explicitly.
    // ------------------------------------------------------------------
    if "`nolabel'" != "" {
        local firstrowtype "none"
    }
    else if `"`firstrow'"' != "" {
        local fr = lower(strtrim(`"`firstrow'"'))
        if "`fr'" == "variables" {
            local firstrowtype "variables"
        }
        else if "`fr'" == "varlabels" {
            local firstrowtype "varlabels"
        }
        else if "`fr'" == "none" {
            local firstrowtype "none"
        }
        else {
            display as error "firstrow() must be variables, varlabels, or none"
            exit 198
        }
    }
    else {
        // Smart default: replace gets variable names header,
        // append gets no header (prevents duplicate header rows)
        if "`writemode'" == "replace" {
            local firstrowtype "variables"
        }
        else {
            local firstrowtype "none"
        }
    }

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
    // Handle if/in subsetting via marksample
    // marksample creates a 0/1 touse variable -- Java reads it to
    // filter which observations to export.
    // ------------------------------------------------------------------
    tempvar touse
    marksample touse, novarlist

    quietly count if `touse'
    if r(N) == 0 {
        display as error "no observations selected"
        exit 2000
    }

    local nselected = r(N)

    // ------------------------------------------------------------------
    // Build explicit varlist of non-temporary variables
    // Stata tempvars are real dataset variables while active but should
    // never be exported. We build the export varlist explicitly using
    // ds (describe variables) which lists only permanent user variables,
    // then pass the pipe-delimited list to Java.
    // This approach is immune to any tempvar naming convention.
    // ------------------------------------------------------------------
    quietly ds
    // r(varlist) from ds contains all current variables
    // We remove the touse tempvar from this list explicitly
    local allvars `r(varlist)'
    local exportvars ""
    foreach v of local allvars {
        // Skip the touse marker we created
        if "`v'" != "`touse'" {
            local exportvars "`exportvars' `v'"
        }
    }
    local exportvars = strtrim("`exportvars'")

    // Convert space-separated varlist to pipe-delimited for safe Java passing
    local exportvars_pipe = subinstr(strtrim("`exportvars'"), " ", "|", .)

    if "`verbose'" != "" {
        display as text "Exporting `nselected' observation(s) to " ///
            `"sheet("`sheet'") in `writemode' mode..."'
    }

    // ------------------------------------------------------------------
    // Call Java
    // ------------------------------------------------------------------
    javacall com.gsheets.GsheetsExport execute, jars("gsheets.jar") args( ///
        `"`using'"'              ///  args[0]: spreadsheet ID or URL
        `"`personaldir'"'        ///  args[1]: personal ado dir for token cache
        `"`sheet'"'              ///  args[2]: target sheet/tab name
        "`writemode'"            ///  args[3]: "replace" | "append"
        "`firstrowtype'"         ///  args[4]: "variables" | "varlabels" | "none"
        "`touse'"                ///  args[5]: touse marker variable name
        "`verboseflag'"          ///  args[6]: verbose flag
        `"`exportvars_pipe'"'    ///  args[7]: pipe-delimited export varlist
        `"`generate'"'           ///  args[8]: generate column name (append only)
    )

    // ------------------------------------------------------------------
    // Handle result
    // ------------------------------------------------------------------
    if `"`gs_status'"' == "error" {
        display as error `"`gs_error_msg'"'
        exit 499
    }

    return local nrows_written `"`gs_nrows_written'"'

end
