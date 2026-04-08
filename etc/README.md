# `etc/` Scripts

This directory holds internal project-support scripts and their shared code.

## Layout

- `lib/`
  Shared Clojure namespaces for script support.
- `reports/`
  Reporting and introspection scripts.
- `docs/`
  Documentation-build scripts.
- top-level `*.bb`
  Thin compatibility wrappers around categorized entrypoints.

## Conventions

- Scripts should be small entrypoints that delegate common behavior to `lib/`.
- Shared concerns should live in focused helper namespaces rather than one large
  utility file.
- `oc-scripts.common` owns path resolution, banners, and shared run metadata.
- `oc-scripts.shell` owns shell execution and tool checks.
- `oc-scripts.report` owns table/report formatting.
- Use `clj-format` DSL for table/report formatting.
- Use `clj-figlet` for script banners where that improves readability.
- Use `clj-uuid` for UUID-heavy scripts when its semantics are materially useful.
  Do not add it to every script by default.
- Prefer adding a new categorized script over expanding one large catch-all
  script.

## Current Entry Points

- `bench_report.bb`
- `reports/stats.bb`
- `docs/paper.bb`

Top-level wrappers remain for convenience:

- `stats.bb`
- `paper.bb`
