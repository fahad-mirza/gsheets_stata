{smcl}
{* *! import_gsheet.sthlp - gsheets package v1.0.0}{...}
{vieweralsosee "gs4_auth" "help gs4_auth"}{...}
{vieweralsosee "gs4_get" "help gs4_get"}{...}
{vieweralsosee "gs4_deauth" "help gs4_deauth"}{...}
{title:Title}

{phang}{bf:import gsheet} {hline 2} Import data from a Google Sheet into Stata{p_end}

{title:Syntax}

{phang2}{bf:import gsheet} {cmdab:using} {it:sheet_id_or_url}
    [{cmd:,}
    {cmdab:sheet(}{it:string}{cmd:)}
    {cmdab:range(}{it:string}{cmd:)}
    {cmdab:firstrow}
    {cmdab:clear}
    {cmdab:cores(}{it:#}{cmd:)}
    {cmdab:verbose}
    ]{p_end}

{title:Description}

{pstd}
{cmd:import gsheet} reads data from a Google Sheet directly into Stata.
Authentication must be set up first with {helpb gs4_auth}.
{p_end}

{pstd}
The sheet can be specified as a bare spreadsheet ID or a full Google Sheets
browser URL. Both forms are accepted:
{p_end}

{phang2}{cmd:import_gsheet "1BzfL0kZUz1TsI5zxJF1WNF01IxvC67FbOJUiiGMZ_mQ", firstrow clear}{p_end}
{phang2}{cmd:import_gsheet "https://docs.google.com/spreadsheets/d/.../edit?usp=sharing", firstrow clear}{p_end}

{pstd}
If data is already in memory, {cmd:clear} must be specified or Stata will
abort with an error (same behaviour as {helpb import excel}).
{p_end}

{title:Options}

{phang}{cmdab:sheet(}{it:string}{cmd:)} Name of the sheet/tab to read. If omitted,
the first visible sheet is used. Sheet names are case-insensitive.{p_end}

{phang}{cmdab:range(}{it:string}{cmd:)} Cell range in A1 notation, e.g. {cmd:range("A1:E500")}.
If omitted, the entire sheet is read up to the last populated cell.{p_end}

{phang}{cmdab:firstrow} Treat the first row of data as variable names. Variable names
are sanitised to comply with Stata naming rules (spaces and hyphens replaced
with underscores, non-alphanumeric characters removed, truncated to 32 chars).{p_end}

{phang}{cmdab:clear} Clear data in memory before importing. Required if the current
dataset has observations or variables.{p_end}

{phang}{cmdab:cores(}{it:#}{cmd:)} Number of parallel threads for reading large sheets.
Default (0) auto-detects available CPU cores, capped at 6 to stay within
Google API rate limits. Only activates for sheets requiring chunked reads.{p_end}

{phang}{cmdab:verbose} Display detailed progress: sheet name, range, row count,
variable creation summary.{p_end}

{title:Column type inference}

{pstd}
Column types are inferred from the data:{p_end}
{phang2}- If {bf:every} non-empty cell in a column is numeric: stored as {cmd:double}{p_end}
{phang2}- If {bf:any} cell in a column is a string: stored as {cmd:str#} (width = longest value){p_end}
{phang2}- Empty cells become {cmd:.} (Stata missing) in numeric columns and {cmd:""} in string columns{p_end}

{pstd}
Note: if {cmd:firstrow} is used, the header cell is also checked. A column
with a text header but all-numeric data values will be typed as string because
the header cell is a string. This matches the behaviour of {helpb import excel}.
{p_end}

{title:Examples}

{pstd}Basic import:{p_end}
{phang}{cmd:. import_gsheet "1BzfL0kZUz1TsI5zxJF1WNF01IxvC67FbOJUiiGMZ_mQ", clear}{p_end}

{pstd}First row as variable names:{p_end}
{phang}{cmd:. import_gsheet "SHEET_ID", firstrow clear}{p_end}

{pstd}Specific tab:{p_end}
{phang}{cmd:. import_gsheet "SHEET_ID", sheet("Survey Data") firstrow clear}{p_end}

{pstd}Specific range:{p_end}
{phang}{cmd:. import_gsheet "SHEET_ID", range("A1:F200") firstrow clear}{p_end}

{pstd}Full URL:{p_end}
{phang}{cmd:. import_gsheet "https://docs.google.com/spreadsheets/d/SHEET_ID/edit?usp=sharing", firstrow clear}{p_end}

{pstd}Verbose output:{p_end}
{phang}{cmd:. import_gsheet "SHEET_ID", firstrow clear verbose}{p_end}

{pstd}Check what was loaded:{p_end}
{phang}{cmd:. display r(nobs)}{p_end}
{phang}{cmd:. display r(nvars)}{p_end}

{title:Stored results}

{pstd}{cmd:import_gsheet} stores the following in {cmd:r()}:{p_end}
{synoptset 15 tabbed}{...}
{synopt:{cmd:r(nobs)}}number of observations loaded{p_end}
{synopt:{cmd:r(nvars)}}number of variables loaded{p_end}

{title:Authentication}

{pstd}
Run {helpb gs4_auth} before using {cmd:import gsheet}. Three methods are available:{p_end}

{phang2}{cmd:gs4_auth, apikey("AIzaSy...")} {hline 2} API key for public sheets{p_end}
{phang2}{cmd:gs4_auth, clientid("...") clientsecret("...")} {hline 2} OAuth2 for private/team sheets{p_end}
{phang2}{cmd:gs4_auth, serviceaccount("key.json")} {hline 2} service account for automated workflows{p_end}

{title:Author}

{pstd}Fahad Mirza{p_end}

{title:Also see}

{psee}{helpb gs4_auth}, {helpb gs4_get}, {helpb gs4_deauth}{p_end}
