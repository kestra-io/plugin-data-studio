package io.kestra.plugin.datastudio.assets;

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
import io.kestra.plugin.datastudio.AbstractDataStudioTask;
import io.kestra.plugin.datastudio.DataStudioClient;
import io.kestra.plugin.datastudio.FetchResults;
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
    title = "Search Data Studio assets",
    description = "Search reports and data sources the authenticated Workspace user can access. The API accepts one asset type per call; this task queries each type you list and merges the pages."
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
        description = "Asset types to search. Each value is queried separately because the API accepts exactly one type per request. Allowed values are REPORT and DATA_SOURCE."
    )
    @PluginProperty(group = "source")
    @NotNull
    private Property<List<String>> assetTypes;

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
        description = "One of `title`, `last_viewed_by_me`, `create_time`, `last_accessed_time`, or `id`."
    )
    @PluginProperty(group = "source")
    private Property<String> orderBy;

    @Schema(
        title = "Page size",
        description = "Number of assets to request per page. The API default is 1000."
    )
    @PluginProperty(group = "source")
    private Property<Integer> pageSize;

    @Schema(
        title = "Fetch type",
        description = "FETCH returns every asset in the execution context. FETCH_ONE returns the first asset. STORE writes an Ion file to internal storage. NONE returns only the count."
    )
    @PluginProperty(group = "main")
    @NotNull
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        List<String> types = runContext.render(this.assetTypes).asList(String.class);
        if (types.isEmpty()) {
            throw new IllegalArgumentException("assetTypes must contain REPORT or DATA_SOURCE");
        }
        String title = runContext.render(this.title).as(String.class).orElse(null);
        String owner = runContext.render(this.owner).as(String.class).orElse(null);
        Boolean includeTrashed = runContext.render(this.includeTrashed).as(Boolean.class).orElse(false);
        String orderBy = runContext.render(this.orderBy).as(String.class).orElse(null);
        Integer pageSize = runContext.render(this.pageSize).as(Integer.class).orElse(null);
        FetchType renderedFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        List<Asset> assets = new ArrayList<>();
        try (DataStudioClient client = this.open(runContext)) {
            for (String assetType : types) {
                runContext.logger().info("Searching Data Studio assets of type {}", assetType);
                assets.addAll(client.searchAll(assetType, title, owner, includeTrashed, orderBy, pageSize));
            }
        }

        FetchResults.Stored<Asset> stored = FetchResults.apply(runContext, renderedFetchType, assets);
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
