Search reports and data sources, read asset metadata, and manage who can access them.

Data Studio is Google's BI product for reports and data sources. Its API covers asset inventory and permissions for a Google Workspace or Cloud Identity organization. It does not refresh datasets or render reports.

Authenticate with a service account JSON secret through domain-wide delegation, or pass an OAuth access token. Read tasks request `https://www.googleapis.com/auth/datastudio.readonly`. Add and Revoke request `https://www.googleapis.com/auth/datastudio`. Set `subject` to the Workspace user the service account should impersonate.

## Tasks

- `assets.Search` searches reports and data sources. Use `fetchType` of `FETCH`, `FETCH_ONE`, or `STORE`.
- `assets.Get` walks search pages until the asset name matches. The API has no get-by-id method. Set `includeTrashed` to look in the trash.
- `assets.Trigger` polls for created or updated assets. The watermark key includes the flow id and the trigger id. The first poll only records the current assets. A search past `maxPages` (default 50) fails and leaves the watermark in place.
- `permissions.Get` lists who has access to an asset.
- `permissions.Add` grants `VIEWER` or `EDITOR` to `user:`, `group:`, `serviceAccount:`, and `domain:` members. `allUsers` requires `linkRole` (`LINK_VIEWER` or `LINK_EDITOR`) and `allowLinkSharing: true`. `domain:` can use either role and also requires `allowLinkSharing`. Named members and link members cannot share one call, because the API accepts one role per request.
- `permissions.Revoke` removes members. Each member must use `user:`, `group:`, `serviceAccount:`, `domain:`, or `allUsers`.
