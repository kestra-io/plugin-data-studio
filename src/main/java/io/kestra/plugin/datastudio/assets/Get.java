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
    description = "Load metadata for one report or data source. The API exposes search rather than a get-by-id method, so this task searches the given asset types and returns the asset whose name matches."
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

    @Override
    public Output run(RunContext runContext) throws Exception {
        String assetName = runContext.render(this.assetName).as(String.class).orElseThrow();
        List<String> types = runContext.render(this.assetTypes).asList(String.class);
        if (types.isEmpty()) {
            types = List.of("REPORT", "DATA_SOURCE");
        }

        try (DataStudioClient client = this.open(runContext)) {
            for (String assetType : types) {
                for (Asset asset : client.searchAll(assetType, null, null, false, null, null)) {
                    if (assetName.equals(asset.getName())) {
                        runContext.logger().info("Found Data Studio asset {}", assetName);
                        return Output.builder().asset(asset).build();
                    }
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
