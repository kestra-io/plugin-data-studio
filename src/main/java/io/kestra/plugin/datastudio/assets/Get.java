package io.kestra.plugin.datastudio.assets;

import java.util.List;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datastudio.AbstractDataStudioTask;
import io.kestra.plugin.datastudio.DataStudioClient;
import io.kestra.plugin.datastudio.models.Asset;
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
    title = "Get a Data Studio asset",
    description = "Load metadata for one report or data source. The API has no get-by-id method, so this task walks search pages for each asset type and stops at the first name match. On a large organization that can be many pages. Live assets are searched first. `includeTrashed` then searches the trash, because the API returns only trashed assets when that flag is true. The task fails if a scan exceeds `maxPages` without a match."
)
@Plugin(
    examples = {
        @Example(
            title = "Get report metadata",
            full = true,
            code = """
                id: assets_get
                namespace: company.team

                inputs:
                  - id: asset_id
                    type: STRING

                tasks:
                  - id: get
                    type: io.kestra.plugin.datastudio.assets.Get
                    serviceAccount: "{{ secret('GCP_SERVICE_ACCOUNT_JSON') }}"
                    assetName: "{{ inputs.asset_id }}"
                    assetTypes:
                      - REPORT
                """
        )
    }
)
public class Get extends AbstractDataStudioTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Asset name",
        description = "The Data Studio asset ID returned by Search as `name`."
    )
    @PluginProperty(group = "source")
    @NotNull
    private Property<String> assetName;

    @Schema(
        title = "Asset types",
        description = "Types to search while looking up the asset. Defaults to REPORT and DATA_SOURCE."
    )
    @PluginProperty(group = "source")
    @Builder.Default
    private Property<List<AssetType>> assetTypes = Property.ofValue(List.of(AssetType.REPORT, AssetType.DATA_SOURCE));

    @Schema(
        title = "Include trashed assets",
        description = "When true, search live assets first and then the trash. The API's `includeTrashed=true` returns only trashed assets, so a trashed asset is not visible in the live scan. When false, trashed assets are not found."
    )
    @PluginProperty(group = "source")
    @NotNull
    @Builder.Default
    private Property<Boolean> includeTrashed = Property.ofValue(false);

    @Schema(
        title = "Maximum pages",
        description = "Fail when one asset type needs more pages than this before the name matches. Default 50."
    )
    @PluginProperty(group = "advanced")
    @NotNull
    @Builder.Default
    private Property<@Min(1) Integer> maxPages = Property.ofValue(DataStudioClient.DEFAULT_MAX_PAGES);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rAssetName = runContext.render(this.assetName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("assetName is required"));
        var rAssetTypes = runContext.render(this.assetTypes).asList(AssetType.class);
        if (rAssetTypes.isEmpty()) {
            rAssetTypes = List.of(AssetType.REPORT, AssetType.DATA_SOURCE);
        }
        var rIncludeTrashed = runContext.render(this.includeTrashed).as(Boolean.class).orElse(false);
        var rMaxPages = runContext.render(this.maxPages).as(Integer.class).orElse(DataStudioClient.DEFAULT_MAX_PAGES);
        if (rMaxPages < 1) {
            throw new IllegalArgumentException("maxPages must be at least 1");
        }

        try (DataStudioClient client = this.open(runContext)) {
            Asset live = find(client, rAssetTypes, rAssetName, false, rMaxPages);
            if (live != null) {
                runContext.logger().info("Found Data Studio asset {}", rAssetName);
                return Output.builder().asset(live).build();
            }
            if (rIncludeTrashed) {
                Asset trashed = find(client, rAssetTypes, rAssetName, true, rMaxPages);
                if (trashed != null) {
                    runContext.logger().info("Found trashed Data Studio asset {}", rAssetName);
                    return Output.builder().asset(trashed).build();
                }
            }
        }
        throw new IllegalArgumentException("No Data Studio asset named " + rAssetName + ". Check assetName and assetTypes. Set includeTrashed to true to search the trash.");
    }

    private static Asset find(DataStudioClient client, List<AssetType> assetTypes, String assetName, boolean trashed, int maxPages) throws Exception {
        for (AssetType assetType : assetTypes) {
            Asset asset = client.findByName(assetType.name(), assetName, trashed, maxPages);
            if (asset != null) {
                return asset;
            }
        }
        return null;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Asset metadata")
        private final Asset asset;
    }
}
