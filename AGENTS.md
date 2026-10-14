# Kestra Google Data Studio Plugin

## What

- Provides plugin components under `io.kestra.plugin.datastudio`.
- Includes `assets.Search`, `assets.Get`, `assets.Trigger`, `permissions.Get`, `permissions.Add`, and `permissions.Revoke`.

## Why

- Flows can inventory Data Studio reports and data sources and change who has access to them.
- Governance stays in Kestra instead of a manual pass through the Data Studio sharing dialog.
- Access reviews and revocations can run on a schedule next to the existing GCP plugin.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `datastudio`
- `datastudio.assets`
- `datastudio.permissions`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.datastudio.assets.Search`
- `io.kestra.plugin.datastudio.assets.Get`
- `io.kestra.plugin.datastudio.assets.Trigger`
- `io.kestra.plugin.datastudio.permissions.Get`
- `io.kestra.plugin.datastudio.permissions.Add`
- `io.kestra.plugin.datastudio.permissions.Revoke`

### Project Structure

```
plugin-data-studio/
├── src/main/java/io/kestra/plugin/datastudio/
├── src/test/java/io/kestra/plugin/datastudio/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
