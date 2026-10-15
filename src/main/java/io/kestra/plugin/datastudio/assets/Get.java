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
import io.swagger.v3.oas.annotations.media.Schema;
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
    description = "Load metadata for one report or data source. The API has no get-by-id method, so this task walks search pages for each asset type and stops at the first name match. On a large organization that can be many pages. Trashed assets are skipped unless `includeTrashed` is true. The task fails if the scan exceeds `maxPages` without a match, instead of reporting that the asset is missing."
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
    private Property<List<String>> assetTypes = Property.ofValue(List.of("REPORT", "DATA_SOURCE"));

    @Schema(
        title = "Include trashed assets",
        description = "When true, the lookup also searches the trash. When false, trashed assets are not found."
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
    private Property<Integer> maxPages = Property.ofValue(DataStudioClient.DEFAULT_MAX_PAGES);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String assetName = runContext.render(this.assetName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("assetName is required"));
        List<String> types = runContext.render(this.assetTypes).asList(String.class);
        if (types.isEmpty()) {
            types = List.of("REPORT", "DATA_SOURCE");
        }
        boolean includeTrashed = runContext.render(this.includeTrashed).as(Boolean.class).orElse(false);
        int maxPages = runContext.render(this.maxPages).as(Integer.class).orElse(DataStudioClient.DEFAULT_MAX_PAGES);

        try (DataStudioClient client = this.open(runContext)) {
            for (String assetType : types) {
                Asset asset = client.findByName(assetType, assetName, includeTrashed, maxPages);
                if (asset != null) {
                    runContext.logger().info("Found Data Studio asset {}", assetName);
                    return Output.builder().asset(asset).build();
                }
            }
        }
        throw new IllegalArgumentException("No Data Studio asset named " + assetName);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Asset metadata")
        private final Asset asset;
    }
}
