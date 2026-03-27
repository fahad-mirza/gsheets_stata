# gsheets — Google Sheets for Stata

[![Version](https://img.shields.io/badge/version-1.1.0-blue.svg)](https://github.com/fahad-mirza/gsheets)
[![Stata](https://img.shields.io/badge/Stata-17%2B-red.svg)](https://www.stata.com)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

A Stata package for direct integration with Google Sheets via the Google Sheets API v4. Import any Google Sheet directly into Stata and export Stata datasets back to Google Sheets — without leaving your workflow.

The first properly engineered, authenticated, API-level Google Sheets package for Stata.

---

## Features

- **Import** any Google Sheet into Stata — public or private
- **Export** Stata datasets to Google Sheets with `replace` or `append`
- **Schema-aware append** — mirrors Stata's own `append` command for column reconciliation
- **Parallel reads** for large sheets (10,000+ rows) using multiple threads
- **`if`/`in` subsetting** on export — mirrors `export excel`
- **Auto-create** sheet tabs that don't exist yet
- **Datetime audit column** with `generate()` option on append
- Works with **free Google accounts** — no paid tier required

---

## Installation

### From SSC (once published)
```stata
ssc install gsheets
```

### From GitHub
```stata
net install gsheets, from("https://raw.githubusercontent.com/fahad-mirza/gsheets/main/dist")
```

### From source
1. Clone this repository
2. Run `fetch_gson.bat` to download the Gson dependency
3. Run `build.bat` to compile and install

---

## Quick start

```stata
* Step 1 -- authenticate (one time only)
gs4_auth

* Step 2 -- import a sheet
import_gsheet "https://docs.google.com/spreadsheets/d/YOUR_ID/edit", firstrow clear

* Step 3 -- export results
export_gsheet using "YOUR_SHEET_ID", sheet("Results") replace
```

---

## Authentication

| Method | Setup | import_gsheet | export_gsheet |
|---|---|---|---|
| **`gs4_auth`** (built-in) | None — just sign in | ✅ | ✅ |
| API key | 5 min | ✅ public only | ❌ |
| Service account | 15 min | ✅ | ✅ |

The simplest path — just run `gs4_auth` and sign in with your Google account in the browser. No Google Cloud setup needed.

> **Note:** `export_gsheet` requires OAuth2 or service account authentication.
> API keys are read-only and cannot write to sheets.

### Setting up OAuth2 (for `export_gsheet` and private sheets)

1. Go to [console.cloud.google.com](https://console.cloud.google.com)
2. Create a project → enable **Google Sheets API**
3. **OAuth consent screen** → External → fill in app name and email → **Publish App**
4. **Credentials** → **+ Create Credentials** → **OAuth 2.0 Client ID** → Desktop app
5. Copy the Client ID and Secret

```stata
gs4_auth, clientid("YOUR_ID.apps.googleusercontent.com") clientsecret("YOUR_SECRET")
```

Once authenticated, credentials are cached and refresh automatically.

---

## Command reference

### `gs4_auth` — Authenticate

```stata
gs4_auth                                        // built-in credentials (browser)
gs4_auth, clientid("...") clientsecret("...")   // your own OAuth2 credentials
gs4_auth, serviceaccount("path\to\key.json")    // service account (no browser)
gs4_auth, apikey("AIzaSy...")                   // API key (public sheets, read-only)
gs4_auth, status                                // check current auth status
gs4_deauth                                      // clear cached credentials
```

### `gs4_get` — Spreadsheet metadata

```stata
gs4_get "SHEET_ID_OR_URL"
gs4_get "SHEET_ID_OR_URL", verbose

display r(title)        // spreadsheet name
display r(nsheets)      // number of tabs
display r(sheet_names)  // pipe-delimited tab names
```

### `import_gsheet` — Import data

```stata
import_gsheet "SHEET_ID_OR_URL", firstrow clear
import_gsheet "SHEET_ID_OR_URL", sheet("Data") firstrow clear
import_gsheet "SHEET_ID_OR_URL", range("A1:F500") firstrow clear
import_gsheet "SHEET_ID_OR_URL", firstrow clear cores(4) verbose
```

**Returns:** `r(nobs)`, `r(nvars)`

### `export_gsheet` — Export data

```stata
* Replace sheet content
export_gsheet using "SHEET_ID", sheet("Results") replace

* Export subset
export_gsheet if year >= 2020 using "SHEET_ID", sheet("Results") replace
export_gsheet in 1/100 using "SHEET_ID", sheet("Results") replace

* Append with schema reconciliation
export_gsheet using "SHEET_ID", sheet("Panel") append

* Append with datetime audit column
export_gsheet using "SHEET_ID", sheet("Panel") append generate(_append_dt)

* Variable labels as header
export_gsheet using "SHEET_ID", sheet("Results") replace firstrow(varlabels)
```

**Returns:** `r(nrows_written)`

---

## Schema-aware append

`export_gsheet, append` mirrors Stata's own `append` command:

| Situation | Result |
|---|---|
| Variable in **both** datasets | Aligned by column name |
| Variable in **new data only** | Added as new column; old rows get empty cell |
| Variable in **existing sheet only** | New rows get empty cell |

```stata
* Initial export
export_gsheet using "SHEET_ID", sheet("Panel") replace       // creates with 6 cols

* Later -- append 7-column dataset
export_gsheet using "SHEET_ID", sheet("Panel") append        // extends to 7 cols
                                                              // old rows: col 7 empty
                                                              // new rows: col 7 filled
```

---

## Requirements

- **Stata 17+** (requires Java 11+, bundled with Stata 17)
- **Google account** (free Gmail works)
- Internet connection

---

## Building from source

```bash
# 1. Download Gson (only needed once)
fetch_gson.bat

# 2. Compile and install
build.bat
```

The build script uses Stata's bundled JDK — no separate Java installation needed.

---

## Architecture

gsheets uses a Java/SFI plugin architecture:

- **Java layer** — all HTTP, OAuth2, JSON parsing, parallel chunking
- **Stata layer** — syntax parsing, options, user messages
- **Single fat JAR** — only external dependency is Gson (~260 KB), shaded in

See [`docs/gsheets_architecture.docx`](docs/gsheets_architecture.docx) for the full design document and [`project_memory.md`](project_memory.md) for development state.

---

## License

MIT — see [LICENSE](LICENSE)

## Author

Fahad Mirza

## Citation

If you use gsheets in your research, please cite:

```
Mirza, F. (2026). gsheets: Google Sheets integration for Stata.
Available from https://github.com/fahad-mirza/gsheets
```
