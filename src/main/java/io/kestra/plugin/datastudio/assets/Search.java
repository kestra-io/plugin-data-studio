package io.kestra.plugin.datastudio.assets;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.datastudio.AbstractDataStudioTask;
import io.kestra.plugin.datastudio.DataStudioClient;
import io.kestra.plugin.datastudio.FetchResults;
import io.kestra.plugin.datastudio.models.Asset;
import io.kestra.plugin.datastudio.models.AssetOrder;
import io.kestra.plugin.datastudio.models.AssetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Search Data Studio assets",
    description = "Search reports and data sources the authenticated Workspace user can access. The API accepts one asset type per call; this task queries each type you list and merges the pages. The task fails when a query exceeds `maxPages` instead of returning a partial list. `STORE` writes each page to the Ion file as it arrives."
)
@Plugin(
    examples = {
        @Example(
            title = "List all reports owned by a user",
            full = true,
            code = """
                id: assets_search
                namespace: company.team

                inputs:
                  - id: owner_email
                    type: STRING
                    defaults: owner@acme.com

                tasks:
                  - id: search
                    type: io.kestra.plugin.datastudio.assets.Search
                    serviceAccount: "{{ secret('GCP_SERVICE_ACCOUNT_JSON') }}"
                    assetTypes:
                      - REPORT
                    owner: "{{ inputs.owner_email }}"
                """
        )
    }
)
public class Search extends AbstractDataStudioTask implements RunnableTask<Search.Output> {

    @Schema(
        title = "Asset types",
        description = "Asset types to search. Each value is queried separately because the API accepts exactly one type per request. Allowed values are REPORT and DATA_SOURCE. UNKNOWN is output-only and is rejected here."
    )
    @PluginProperty(group = "source")
    @NotNull
    private Property<List<AssetType>> assetTypes;

    @Schema(
        title = "Search string",
        description = "Matched against the asset title and description. Also accepts the API advanced filters, such as `creator:me` or `parentWorkspace:<id>`."
    )
    @PluginProperty(group = "source")
    private Property<String> title;

    @Schema(
        title = "Owner",
        description = "Email of the asset owner."
    )
    @PluginProperty(group = "source")
    private Property<String> owner;

    @Schema(
        title = "Include trashed assets",
        description = "When true, only trashed assets are returned. When false, only assets that are not in the trash are returned."
    )
    @PluginProperty(group = "source")
    @NotNull
    @Builder.Default
    private Property<Boolean> includeTrashed = Property.ofValue(false);

    @Schema(
        title = "Order by",
        description = "Sort field sent to the API. `TITLE`, `LAST_VIEWED_BY_ME`, `CREATE_TIME`, `LAST_ACCESSED_TIME`, or `ID`."
    )
    @PluginProperty(group = "source")
    private Property<AssetOrder> orderBy;

    @Schema(
        title = "Page size",
        description = "Number of assets to request per page. Must be at least 1. The API default is 1000."
    )
    @PluginProperty(group = "source")
    private Property<@Min(1) Integer> pageSize;

    @Schema(
        title = "Maximum pages",
        description = "Fail when one asset type needs more pages than this. Default 50. A partial result is never returned."
    )
    @PluginProperty(group = "advanced")
    @NotNull
    @Builder.Default
    private Property<@Min(1) Integer> maxPages = Property.ofValue(DataStudioClient.DEFAULT_MAX_PAGES);

    @Schema(
        title = "Fetch type",
        description = "STORE writes an Ion file and is the right choice for an organisation-wide inventory. FETCH returns every asset in the execution context and is meant for a small result set. FETCH_ONE returns the first asset from the first page that has one. NONE returns only the count."
    )
    @PluginProperty(group = "main")
    @NotNull
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rAssetTypes = runContext.render(this.assetTypes).asList(AssetType.class);
        if (rAssetTypes.isEmpty()) {
            throw new IllegalArgumentException("assetTypes must contain REPORT or DATA_SOURCE");
        }
        AssetType.rejectUnknownRequest(rAssetTypes);
        var rTitle = runContext.render(this.title).as(String.class).orElse(null);
        var rOwner = runContext.render(this.owner).as(String.class).orElse(null);
        var rIncludeTrashed = runContext.render(this.includeTrashed).as(Boolean.class).orElse(false);
        var rOrderBy = runContext.render(this.orderBy).as(AssetOrder.class).map(AssetOrder::getApiValue).orElse(null);
        var rPageSize = runContext.render(this.pageSize).as(Integer.class).orElse(null);
        var rMaxPages = runContext.render(this.maxPages).as(Integer.class).orElse(DataStudioClient.DEFAULT_MAX_PAGES);
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        if (rMaxPages < 1 || (rPageSize != null && rPageSize < 1)) {
            throw new IllegalArgumentException("maxPages and pageSize must be at least 1");
        }

        if (FetchType.STORE.equals(rFetchType)) {
            File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
            int count = 0;
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(tempFile));
                 DataStudioClient client = this.open(runContext)) {
                for (AssetType assetType : rAssetTypes) {
                    runContext.logger().info("Searching Data Studio assets of type {}", assetType);
                    int[] written = {0};
                    client.consumeSearch(assetType.name(), rTitle, rOwner, rIncludeTrashed, rOrderBy, rPageSize, rMaxPages, asset -> {
                        FileSerde.write(output, asset);
                        written[0]++;
                    });
                    count += written[0];
                }
            }
            return Output.builder()
                .size(count)
                .uri(runContext.storage().putFile(tempFile))
                .build();
        }

        if (FetchType.FETCH_ONE.equals(rFetchType)) {
            try (DataStudioClient client = this.open(runContext)) {
                for (AssetType assetType : rAssetTypes) {
                    var page = client.search(assetType.name(), rTitle, rOwner, rIncludeTrashed, rOrderBy, rPageSize, null);
                    if (page.getAssets() != null && !page.getAssets().isEmpty()) {
                        return Output.builder().size(1).asset(page.getAssets().getFirst()).build();
                    }
                }
            }
            return Output.builder().size(0).build();
        }

        if (FetchType.NONE.equals(rFetchType)) {
            int count = 0;
            try (DataStudioClient client = this.open(runContext)) {
                for (AssetType assetType : rAssetTypes) {
                    int[] seen = {0};
                    client.consumeSearch(assetType.name(), rTitle, rOwner, rIncludeTrashed, rOrderBy, rPageSize, rMaxPages, asset -> seen[0]++);
                    count += seen[0];
                }
            }
            return Output.builder().size(count).build();
        }

        List<Asset> assets = new ArrayList<>();
        try (DataStudioClient client = this.open(runContext)) {
            for (AssetType assetType : rAssetTypes) {
                runContext.logger().info("Searching Data Studio assets of type {}", assetType);
                assets.addAll(client.searchAll(assetType.name(), rTitle, rOwner, rIncludeTrashed, rOrderBy, rPageSize, rMaxPages));
            }
        }

        FetchResults.Stored<Asset> stored = FetchResults.apply(runContext, rFetchType, assets);
        return Output.builder()
            .size(stored.size())
            .uri(stored.uri())
            .asset(stored.one())
            .assets(stored.many())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of assets")
        private final Integer size;

        @Schema(
            title = "Internal storage URI",
            description = "Ion file of the assets. Set only when `fetchType` is STORE."
        )
        private final URI uri;

        @Schema(
            title = "First asset",
            description = "Set only when `fetchType` is FETCH_ONE."
        )
        private final Asset asset;

        @Schema(
            title = "Assets",
            description = "Set only when `fetchType` is FETCH."
        )
        private final List<Asset> assets;
    }
}
