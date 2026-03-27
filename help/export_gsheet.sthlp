{smcl}
{* *! export_gsheet.sthlp - gsheets package v1.1.0}{...}
{vieweralsosee "gs4_auth" "help gs4_auth"}{...}
{vieweralsosee "gs4_get" "help gs4_get"}{...}
{vieweralsosee "import_gsheet" "help import_gsheet"}{...}
{title:Title}

{phang}{bf:export gsheet} {hline 2} Export Stata dataset to a Google Sheet{p_end}

{title:Syntax}

{phang2}{bf:export_gsheet} [{it:if}] [{it:in}] {cmdab:using} {it:sheet_id_or_url}
    [{cmd:,}
    {cmdab:sheet(}{it:string}{cmd:)}
    {cmdab:replace}
    {cmdab:append}
    {cmdab:firstrow(}{it:string}{cmd:)}
    {cmdab:nolabel}
    {cmdab:verbose}
    ]{p_end}

{title:Description}

{pstd}
{cmd:export_gsheet} writes the current Stata dataset to a Google Sheet.
Supports {it:if} and {it:in} qualifiers to export a subset of observations,
mirroring the behaviour of {helpb export excel}.
{p_end}

{pstd}
{bf:Authentication requirement:} {cmd:export_gsheet} requires OAuth2 or service
account authentication. {bf:API key mode cannot write to sheets} -- it is
read-only by design in the Google Sheets API. See {it:Authentication} below.
{p_end}

{title:Options}

{phang}{cmdab:sheet(}{it:string}{cmd:)} Name of the target sheet/tab.
Default is {cmd:Sheet1}.{p_end}

{phang}{cmdab:replace} Clear existing content in the sheet and write from scratch.
The first row will contain variable names (or labels if {cmd:firstrow(varlabels)}
is specified).{p_end}

{phang}{cmdab:append} Add rows after the last populated row in the sheet.
Schema-aware: reads the existing header row and reconciles column order
automatically (see {it:Schema-aware append} below).
Default header behaviour in append mode is {cmd:none} (no duplicate header).{p_end}

{phang}{cmdab:firstrow(}{it:string}{cmd:)} Controls the header row written to the sheet:{p_end}
{phang2}{cmd:firstrow(variables)} write variable names as header (default for {cmd:replace}){p_end}
{phang2}{cmd:firstrow(varlabels)} write variable labels as header (falls back to name if no label){p_end}
{phang2}{cmd:firstrow(none)} no header row written{p_end}

{phang}{cmdab:nolabel} Suppress header row. Equivalent to {cmd:firstrow(none)}.{p_end}

{phang}{cmdab:verbose} Display progress: row counts, chunk information, schema reconciliation details.{p_end}

{title:Authentication}

{pstd}
{bf:API key mode does not support export_gsheet.}
The Google Sheets API restricts API keys to read-only access.
You must use OAuth2 (for personal and team sheets) or a service account
(for automated/server workflows).
{p_end}

{pstd}{bf:Setting up OAuth2 (one-time setup):}{p_end}

{phang2}1. Go to {browse "https://console.cloud.google.com"}{p_end}
{phang2}2. Create or select a project. Enable the {bf:Google Sheets API}:{p_end}
{phang3}{bf:APIs & Services} {hline 1} {bf:Library} {hline 1} search {bf:Google Sheets API} {hline 1} {bf:Enable}{p_end}
{phang2}3. Configure the OAuth consent screen:{p_end}
{phang3}{bf:APIs & Services} {hline 1} {bf:OAuth consent screen} {hline 1} choose {bf:External}{p_end}
{phang3}Fill in App name (e.g. {cmd:gsheets-stata}) and your email address{p_end}
{phang3}On the {bf:Test users} tab, add your Google account email {hline 1} {bf:Save}{p_end}
{phang2}4. Create OAuth2 credentials:{p_end}
{phang3}{bf:APIs & Services} {hline 1} {bf:Credentials} {hline 1} {bf:+ Create Credentials} {hline 1} {bf:OAuth 2.0 Client ID}{p_end}
{phang3}Application type: {bf:Desktop app} {hline 1} give it any name {hline 1} {bf:Create}{p_end}
{phang3}Copy the {bf:Client ID} and {bf:Client Secret} shown{p_end}
{phang2}5. Register the redirect URI:{p_end}
{phang3}Click {bf:Edit} on your new credential{p_end}
{phang3}Under {bf:Authorised redirect URIs} click {bf:Add URI} and enter:{p_end}
{phang3}{cmd:http://127.0.0.1:29857}{p_end}
{phang3}Click {bf:Save}{p_end}
{phang2}6. Authenticate in Stata (one time only):{p_end}

{phang2}{cmd:. gs4_auth, clientid("YOUR_CLIENT_ID.apps.googleusercontent.com") clientsecret("YOUR_SECRET")}{p_end}

{pstd}
A browser window opens. Sign in with your Google account and click {bf:Allow}.
Credentials are cached and refresh automatically -- you will not be prompted again.
{p_end}

{pstd}
{bf:Important:} The sheet you are exporting to must be owned by or shared
(with edit access) to the Google account you signed in with.
{p_end}

{pstd}
{bf:Teams:} The same Client ID and Secret can be shared across a team.
Each person runs {cmd:gs4_auth} and signs in with their own Google account.
Each person can only export to sheets their account has edit access to.
{p_end}

{title:Schema-aware append}

{pstd}
{cmd:append} mode mirrors Stata's own {cmd:append} command for column reconciliation:{p_end}

{phang2}{c -} Variables in {bf:both} the new data and the existing sheet:
aligned by column name (case-insensitive){p_end}
{phang2}{c -} Variables in the {bf:new data only}: added as new columns at the right of
the sheet; existing rows get empty cells in those columns{p_end}
{phang2}{c -} Variables in the {bf:existing sheet only}: new rows get empty cells
in those columns{p_end}

{pstd}
{bf:Recommended workflow for append:}{p_end}

{phang}{cmd:* First export -- creates sheet with header + data}{p_end}
{phang}{cmd:. export_gsheet using "SHEET_ID", sheet("Results") replace}{p_end}
{phang}{cmd:}{p_end}
{phang}{cmd:* Later -- append new observations, no duplicate header}{p_end}
{phang}{cmd:. export_gsheet if year == 2024 using "SHEET_ID", sheet("Results") append}{p_end}

{title:Examples}

{pstd}Full dataset, replace:{p_end}
{phang}{cmd:. sysuse auto, clear}{p_end}
{phang}{cmd:. export_gsheet using "SHEET_ID", sheet("auto") replace}{p_end}

{pstd}Subset with if:{p_end}
{phang}{cmd:. export_gsheet if foreign == 1 using "SHEET_ID", sheet("foreign_cars") replace}{p_end}

{pstd}Subset with in:{p_end}
{phang}{cmd:. export_gsheet in 1/20 using "SHEET_ID", sheet("top20") replace}{p_end}

{pstd}Variable labels as header:{p_end}
{phang}{cmd:. export_gsheet using "SHEET_ID", sheet("auto") replace firstrow(varlabels)}{p_end}

{pstd}Append new observations:{p_end}
{phang}{cmd:. export_gsheet if year == 2024 using "SHEET_ID", sheet("Panel") append}{p_end}

{pstd}No header row:{p_end}
{phang}{cmd:. export_gsheet using "SHEET_ID", sheet("Data") replace nolabel}{p_end}

{pstd}Verbose output:{p_end}
{phang}{cmd:. export_gsheet using "SHEET_ID", sheet("auto") replace verbose}{p_end}

{pstd}Full URL:{p_end}
{phang}{cmd:. export_gsheet using "https://docs.google.com/spreadsheets/d/SHEET_ID/edit", sheet("auto") replace}{p_end}

{title:Stored results}

{pstd}{cmd:export_gsheet} stores the following in {cmd:r()}:{p_end}
{synoptset 20 tabbed}{...}
{synopt:{cmd:r(nrows_written)}}number of data rows written (excluding header){p_end}

{title:Authentication summary}

{synoptset 20 tabbed}{...}
{synopt:{bf:Method}}{bf:export_gsheet support}{p_end}
{synopt:API key}Not supported (read-only){p_end}
{synopt:OAuth2 browser flow}Supported -- recommended for personal and team use{p_end}
{synopt:Service account}Supported -- recommended for automated workflows{p_end}

{title:Author}

{pstd}Fahad Mirza{p_end}

{title:Also see}

{psee}{helpb gs4_auth}, {helpb import_gsheet}, {helpb gs4_get}{p_end}
