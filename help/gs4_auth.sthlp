{smcl}
{* *! gs4_auth.sthlp - gsheets package v1.0.0}{...}
{vieweralsosee "gs4_deauth" "help gs4_deauth"}{...}
{vieweralsosee "gs4_get" "help gs4_get"}{...}
{vieweralsosee "import_gsheet" "help import_gsheet"}{...}
{title:Title}

{phang}{bf:gs4_auth} {hline 2} Authenticate with the Google Sheets API{p_end}

{title:Syntax}

{phang2}{bf:gs4_auth}{cmd:,} {cmdab:apikey(}{it:string}{cmd:)}{p_end}
{phang2}{bf:gs4_auth}{cmd:,} {cmdab:clientid(}{it:string}{cmd:)} {cmdab:clientsecret(}{it:string}{cmd:)} [{cmdab:readonly}]{p_end}
{phang2}{bf:gs4_auth}{cmd:,} {cmdab:serviceaccount(}{it:path}{cmd:)}{p_end}
{phang2}{bf:gs4_auth}{cmd:,} {cmdab:status}{p_end}

{title:Description}

{pstd}
{cmd:gs4_auth} authenticates your Stata session with the Google Sheets API.
Credentials are cached to {cmd:c(sysdir_personal)/gsheets_token.json} and
reused automatically across sessions. Access tokens refresh silently when
they expire.
{p_end}

{pstd}
There are three authentication methods depending on your situation:
{p_end}

{phang2}{bf:API key} {hline 2} simplest option; works only with publicly shared sheets
(sheets set to "Anyone with the link can view").{p_end}

{phang2}{bf:OAuth2 browser flow} {hline 2} works with private sheets and team/organisational
sheets. Opens a browser for one-time consent. Each user authenticates with their
own Google account and can access sheets shared with that account.{p_end}

{phang2}{bf:Service account} {hline 2} for automated pipelines and server workflows
with no browser available. A "bot" Google account that accesses only sheets
explicitly shared with its email address.{p_end}

{title:Options}

{phang}{cmdab:apikey(}{it:string}{cmd:)} Google API key from Google Cloud Console.
{bf:Read-only:} API keys can only read publicly shared sheets. They cannot write
to sheets and therefore do not support {helpb export_gsheet}. For write access
use OAuth2 or a service account.{p_end}

{phang}{cmdab:clientid(}{it:string}{cmd:)} OAuth2 client ID from Google Cloud Console.
Must be used together with {cmd:clientsecret()}.{p_end}

{phang}{cmdab:clientsecret(}{it:string}{cmd:)} OAuth2 client secret from Google Cloud Console.
Must be used together with {cmd:clientid()}.{p_end}

{phang}{cmdab:readonly} Request read-only scope. Use when you only need to read sheets
and want to limit the permissions granted. Default is read-write scope (needed
for future {cmd:export_gsheet} support).{p_end}

{phang}{cmdab:serviceaccount(}{it:path}{cmd:)} Path to a Google service account JSON key
file. No browser required. See {it:Getting credentials} below.{p_end}

{phang}{cmdab:status} Display the current authentication status without re-authenticating.{p_end}

{title:Getting credentials}

{pstd}{bf:API key (public sheets only)}{p_end}
{phang2}1. Go to {browse "https://console.cloud.google.com"}{p_end}
{phang2}2. Create or select a project{p_end}
{phang2}3. {bf:APIs & Services} {hline 1} {bf:Library} {hline 1} search {bf:Google Sheets API} {hline 1} {bf:Enable}{p_end}
{phang2}4. {bf:APIs & Services} {hline 1} {bf:Credentials} {hline 1} {bf:+ Create Credentials} {hline 1} {bf:API key}{p_end}
{phang2}5. Copy the key and optionally restrict it to the Sheets API{p_end}

{pstd}{bf:OAuth2 browser flow (private and team sheets)}{p_end}
{phang2}1. Enable Google Sheets API (as above){p_end}
{phang2}2. {bf:APIs & Services} {hline 1} {bf:OAuth consent screen} {hline 1} fill in app name and email{p_end}
{phang2}3. On the {bf:Test users} tab, add the Google accounts that will use this{p_end}
{phang2}4. {bf:Credentials} {hline 1} {bf:+ Create Credentials} {hline 1} {bf:OAuth 2.0 Client ID} {hline 1} {bf:Desktop app}{p_end}
{phang2}5. Copy the Client ID and Client Secret{p_end}
{phang2}6. Edit the credential and add {bf:http://127.0.0.1:29857} as an authorised redirect URI{p_end}

{pstd}{bf:Service account (automated workflows)}{p_end}
{phang2}1. Enable Google Sheets API (as above){p_end}
{phang2}2. {bf:Credentials} {hline 1} {bf:+ Create Credentials} {hline 1} {bf:Service account}{p_end}
{phang2}3. On the service account page, go to {bf:Keys} {hline 1} {bf:Add Key} {hline 1} {bf:JSON}{p_end}
{phang2}4. Save the downloaded JSON file securely{p_end}
{phang2}5. Share each Google Sheet with the service account email address{p_end}

{title:Examples}

{phang}{cmd:. gs4_auth, apikey("AIzaSyD-9tSrke72...")}{p_end}

{phang}{cmd:. gs4_auth, clientid("123456.apps.googleusercontent.com") clientsecret("GOCSPX-...")}{p_end}

{phang}{cmd:. gs4_auth, serviceaccount("C:\keys\myproject-key.json")}{p_end}

{phang}{cmd:. gs4_auth, status}{p_end}

{phang}{cmd:. gs4_deauth}{p_end}

{title:Stored results}

{pstd}{cmd:gs4_auth} stores the following in {cmd:r()}:{p_end}
{synoptset 20 tabbed}{...}
{synopt:{cmd:r(auth_type)}}authentication type: {cmd:oauth2}, {cmd:service_account}, or {cmd:api_key}{p_end}
{synopt:{cmd:r(expires_at)}}Unix timestamp of token expiry (oauth2 and service_account only){p_end}
{synopt:{cmd:r(message)}}confirmation message{p_end}

{title:Authentication capabilities by method}

{synoptset 22 tabbed}{...}
{synopt:{bf:Method}}{bf:import_gsheet}  {bf:export_gsheet}  {bf:gs4_get}{p_end}
{synopt:API key}Public sheets only  {bf:Not supported}  Public sheets only{p_end}
{synopt:OAuth2 browser flow}All accessible sheets  Sheets with edit access  All accessible sheets{p_end}
{synopt:Service account}Shared sheets  Sheets with edit access  Shared sheets{p_end}

{pstd}
{bf:Recommendation:} use OAuth2 for interactive use and service accounts for
automated do-files. API key is convenient for reading public data but cannot
write to any sheet.
{p_end}

{title:Author}

{pstd}Fahad Mirza{p_end}

{title:Also see}

{psee}{helpb gs4_deauth}, {helpb gs4_get}, {helpb import_gsheet}{p_end}
