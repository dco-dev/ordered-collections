# `etc/` Scripts

This directory holds internal project-support scripts and their shared code.

## Layout

- top-level `*.bb`
  Main script entrypoints live directly under `etc/`.
- `lib/`
  Shared support code used by the top-level scripts.

## Conventions

- Scripts should be small entrypoints that delegate common behavior to `lib/`.
- `lib/` should stay shallow. Prefer one semantic support file per concern.
- File names in `lib/` should describe purpose directly and avoid collisions.
- Use `clj-format` DSL for table/report formatting.
- Use `clj-figlet` for script banners where that improves readability.
- Use `clj-uuid` for UUID-heavy scripts when its semantics are materially useful.
  Do not add it to every script by default.
- Prefer adding a new top-level script over growing one catch-all entrypoint.

## Current Entry Points

- `bench_report.bb`
- `stats.bb`
- `paper.bb`
