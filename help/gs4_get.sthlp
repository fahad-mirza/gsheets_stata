{smcl}
{* *! gs4_get.sthlp - gsheets package v1.0.0}{...}
{vieweralsosee "gs4_auth" "help gs4_auth"}{...}
{vieweralsosee "import_gsheet" "help import_gsheet"}{...}
{title:Title}

{phang}{bf:gs4_get} {hline 2} Get metadata about a Google Spreadsheet{p_end}

{title:Syntax}

{phang2}{bf:gs4_get} {it:sheet_id_or_url} [{cmd:,} {cmdab:verbose}]{p_end}

{title:Description}

{pstd}
{cmd:gs4_get} retrieves metadata about a Google Spreadsheet: its title,
spreadsheet ID, number of sheets, and the list of sheet/tab names.
Results are stored in {cmd:r()}.
{p_end}

{pstd}
Use {cmd:gs4_get} to inspect a spreadsheet before importing, to get
sheet names, or to verify that authentication is working correctly.
{p_end}

{title:Options}

{phang}{cmdab:verbose} Display the full sheet list with dimensions (rows x columns
of the allocated grid) for each tab.{p_end}

{title:Examples}

{phang}{cmd:. gs4_get "1BzfL0kZUz1TsI5zxJF1WNF01IxvC67FbOJUiiGMZ_mQ"}{p_end}

{phang}{cmd:. gs4_get "https://docs.google.com/spreadsheets/d/SHEET_ID/edit?usp=sharing", verbose}{p_end}

{phang}{cmd:. display r(title)}{p_end}
{phang}{cmd:. display r(nsheets)}{p_end}
{phang}{cmd:. display r(sheet_names)}{p_end}

{pstd}Parse sheet names (pipe-delimited) into a local macro list:{p_end}
{phang}{cmd:. local names = r(sheet_names)}{p_end}
{phang}{cmd:. local n = r(nsheets)}{p_end}
{phang}{cmd:. forvalues i = 1/`n' {c -(}}{p_end}
{phang}{cmd:.     local sname`i' = word(subinstr("`names'","|"," ",.),`i')}{p_end}
{phang}{cmd:. {c )-}}{p_end}

{title:Stored results}

{pstd}{cmd:gs4_get} stores the following in {cmd:r()}:{p_end}
{synoptset 20 tabbed}{...}
{synopt:{cmd:r(title)}}spreadsheet title{p_end}
{synopt:{cmd:r(id)}}spreadsheet ID{p_end}
{synopt:{cmd:r(nsheets)}}number of sheets/tabs{p_end}
{synopt:{cmd:r(sheet_names)}}pipe-delimited list of sheet names, e.g. {cmd:"Sheet1|Data|Summary"}{p_end}

{title:Note on dimensions}

{pstd}
The row and column counts shown with {cmd:verbose} reflect the {it:allocated grid size}
set by Google Sheets (default 1000 rows x 26 columns for a new sheet), not the
number of cells containing data. Use {helpb import_gsheet} with {cmd:verbose} to
see the actual number of data rows retrieved.
{p_end}

{title:Author}

{pstd}Fahad Mirza{p_end}

{title:Also see}

{psee}{helpb gs4_auth}, {helpb import_gsheet}, {helpb gs4_deauth}{p_end}
