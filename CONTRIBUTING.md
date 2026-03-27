# Contributing to gsheets

Thank you for your interest in contributing to gsheets.

## Development setup

1. Clone the repository
2. Run `fetch_gson.bat` to download the Gson dependency
3. Run `build.bat` to compile and install locally
4. Test in Stata with `gs4_auth, status`

## Project structure

```
ado/          Stata command files (.ado)
java/src/     Java source code (com.gsheets package)
java/lib/     Place gson-2.10.1.jar here (not committed)
help/         Stata help files (.sthlp)
docs/         Architecture documentation
```

## Key conventions

- ASCII-only Java source files (no Unicode literals)
- `jars("gsheets.jar")` — bare filename only in javacall
- All commands that set r() must declare `, rclass`
- Use `SFIToolkit.displayln()` not `display() + \n`
- Never use `{p_end}` in Java strings
- `ThreadLocal<Gson>` always — never share Gson across threads
- Single shared `HttpClient` static instance
- See `project_memory.md` for full conventions

## Submitting changes

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run `build.bat` and test in Stata
5. Submit a pull request with a clear description

## Reporting bugs

Open an issue with:
- Stata version (`version`)
- Java version (`java query`)
- The exact command that failed
- The full error message
