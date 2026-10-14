Search reports and data sources, read asset metadata, and manage who can access them.

Data Studio is the Google BI product formerly named Looker Studio (renamed back in April 2026). Its API covers asset inventory and permissions for a Google Workspace or Cloud Identity organization. It does not refresh datasets or render reports.

Authenticate with a service account JSON secret that has the `https://www.googleapis.com/auth/datastudio` scope through domain-wide delegation, or pass an OAuth access token. Set `subject` to the Workspace user the service account should impersonate.

## Tasks

- `assets.Search` searches reports and data sources. Use `fetchType` of `FETCH`, `FETCH_ONE`, or `STORE`.
- `assets.Get` returns metadata for one asset.
- `assets.Trigger` polls for created or updated assets. The watermark is stored in the namespace KV store. The first poll only records the current assets.
- `permissions.Get` lists who has access to an asset.
- `permissions.Add` grants `VIEWER` or `EDITOR` access. `OWNER` cannot be granted.
- `permissions.Revoke` removes members from an asset.
