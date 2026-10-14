Search Data Studio reports and data sources, and watch them for changes.

`Search` lists assets. The API accepts one asset type per request, so list each type you need. `Get` returns metadata for a single asset id. `Trigger` polls on an interval and starts a flow when an asset is new or its `updateTime` moves past the watermark kept in the namespace KV store.
