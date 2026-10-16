# How to use the Data Studio plugin

Google Data Studio stores reports and data sources for a Google Workspace or Cloud Identity organization. This plugin searches that inventory, reads one asset, and manages who can access it. The [Data Studio API](https://developers.google.com/data-studio/integrate/api) does not refresh datasets or render reports.

## Authentication

Store the key in a [Kestra secret](https://kestra.io/docs/concepts/secret). Share it across tasks with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

- `serviceAccount`: service account JSON secret, with domain-wide delegation. Read tasks and the trigger request `https://www.googleapis.com/auth/datastudio.readonly`. Add and Revoke request `https://www.googleapis.com/auth/datastudio`.
- `accessToken`: OAuth access token secret. When set, the task skips the service-account exchange.
- `subject`: Workspace user the service account should impersonate.
- `baseUrl`: API host. Default `https://datastudio.googleapis.com/v1`.
- `tokenUri`: token endpoint. Default `https://oauth2.googleapis.com/token`.

## Tasks

### Search

Searches one asset type per request and merges the types you list.

- `assetTypes`: `REPORT`, `DATA_SOURCE`, or both. Each type is a separate API call.
- `includeTrashed`: When true, only assets in the trash are returned. When false, only live assets are returned.
- `fetchType`: `STORE` writes an Ion file and is the right choice for an organisation-wide inventory. `FETCH` returns every asset in the execution context and is meant for a small result set. `FETCH_ONE` returns the first asset from the first page that has one. `NONE` returns only the count.
- `maxPages`: Fail when one asset type needs more pages than this. Default 50. A partial list is never returned.

### Get

The API has no get-by-id method, so this task walks search pages and stops at the first name match.

- `assetName`: Asset id to find.
- `includeTrashed`: Search live assets first. When true, a second pass searches the trash, because the API's `includeTrashed=true` returns only trashed assets.
- `maxPages`: Fail when a scan exceeds this many pages. Default 50.

### Get permissions

Lists who has access to one asset, grouped by role.

- `assetName`: Asset id.
- `fetchType`: `FETCH` returns every member, each with its role. `FETCH_ONE` returns the first member. `STORE` writes an Ion file. `NONE` returns only the count. The role map is not returned again.

### Add

Grants access. One call has a single role, so named members and `allUsers` cannot be mixed.

- `role`: `VIEWER` or `EDITOR` for `user:`, `group:`, and `serviceAccount:` members. `domain:` can use this role when `allowLinkSharing` is true.
- `linkRole`: `LINK_VIEWER` or `LINK_EDITOR`. Required for `allUsers`. Also valid for `domain:`.
- `allowLinkSharing`: Leave false unless the flow should make an asset public or domain-wide.
- `members`: `user:`, `group:`, `serviceAccount:`, `domain:`, or `allUsers`.

### Revoke

Removes members from an asset.

- `members`: Each entry must use `user:`, `group:`, `serviceAccount:`, `domain:`, or `allUsers`.

## Triggers

### Trigger

Polls for created or updated assets. The watermark key includes the flow id and the trigger id, so two flows do not share state. The first poll only records the current assets. A search past `maxPages` fails and leaves the watermark in place.

- `assetTypes`: Types to watch. Each value is queried separately.
- `maxPages`: Default 50. The partial asset list is discarded when the cap is hit.
- `maxAssetsInOutput`: Cap on `assets` in the trigger variables. Default 100. `size` stays the full number of changed assets.

Outputs are `title`, `name`, `assetType`, `updateTime`, `size`, and `assets`.
