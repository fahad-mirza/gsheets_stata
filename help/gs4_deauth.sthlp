{smcl}
{* *! gs4_deauth.sthlp - gsheets package v1.0.0}{...}
{vieweralsosee "gs4_auth" "help gs4_auth"}{...}
{vieweralsosee "import_gsheet" "help import_gsheet"}{...}
{title:Title}

{phang}{bf:gs4_deauth} {hline 2} Clear cached Google Sheets credentials{p_end}

{title:Syntax}

{phang2}{bf:gs4_deauth}{p_end}

{title:Description}

{pstd}
{cmd:gs4_deauth} removes the cached credentials written by {helpb gs4_auth}.
The token cache file ({cmd:gsheets_token.json} in {cmd:c(sysdir_personal)})
is deleted.
{p_end}

{pstd}
After running {cmd:gs4_deauth}, you must run {helpb gs4_auth} again before
reading or writing any sheets. Use this command when switching Google accounts,
or to revoke access from this machine.
{p_end}

{title:Examples}

{phang}{cmd:. gs4_deauth}{p_end}

{phang}{cmd:. gs4_auth, status}{p_end}
{phang}{cmd:  gsheets: not authenticated. Run gs4_auth.}{p_end}

{title:Author}

{pstd}Fahad Mirza{p_end}

{title:Also see}

{psee}{helpb gs4_auth}, {helpb gs4_get}, {helpb import_gsheet}{p_end}
