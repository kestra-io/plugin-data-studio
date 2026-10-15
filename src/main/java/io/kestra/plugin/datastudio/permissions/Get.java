package io.kestra.plugin.datastudio.permissions;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import io.kestra.plugin.datastudio.models.Member;
import io.kestra.plugin.datastudio.models.Permissions;
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
    title = "List Data Studio asset permissions",
    description = "List who has access to a report or data source, grouped by role."
)
@Plugin(
    examples = {
        @Example(
            title = "Audit who can view a report",
            full = true,
            code = """
                id: permissions_get
                namespace: company.team

                inputs:
                  - id: asset_id
                    type: STRING

                tasks:
                  - id: get
                    type: io.kestra.plugin.datastudio.permissions.Get
                    serviceAccount: "{{ secret('GCP_SERVICE_ACCOUNT_JSON') }}"
                    assetName: "{{ inputs.asset_id }}"
                """
        )
    }
)
public class Get extends AbstractDataStudioTask implements RunnableTask<Get.Output> {

    @Schema(title = "Asset name", description = "The Data Studio asset ID.")
    @PluginProperty(group = "source")
    @NotNull
    private Property<String> assetName;

    @Schema(
        title = "Fetch type",
        description = "FETCH returns every member in the execution context. FETCH_ONE returns the first member. STORE writes an Ion file of members to internal storage. NONE returns only the count."
    )
    @PluginProperty(group = "main")
    @NotNull
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rAssetName = runContext.render(this.assetName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("assetName is required"));
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        Permissions permissions;
        try (DataStudioClient client = this.open(runContext)) {
            runContext.logger().info("Reading permissions for Data Studio asset {}", rAssetName);
            permissions = client.getPermissions(rAssetName);
        }

        List<Member> members = flatten(permissions);
        FetchResults.Stored<Member> stored = FetchResults.apply(runContext, rFetchType, members);
        return Output.builder()
            .size(stored.size())
            .uri(stored.uri())
            .member(stored.one())
            .members(stored.many())
            .permissions(FetchType.FETCH.equals(rFetchType) ? roleMap(permissions) : null)
            .build();
    }

    static List<Member> flatten(Permissions permissions) {
        List<Member> members = new ArrayList<>();
        if (permissions == null || permissions.getPermissions() == null) {
            return members;
        }
        permissions.getPermissions().forEach((role, value) -> {
            if (value == null || value.getMembers() == null) {
                return;
            }
            for (String member : value.getMembers()) {
                members.add(Member.builder().role(role).member(member).build());
            }
        });
        return members;
    }

    static Map<String, List<String>> roleMap(Permissions permissions) {
        if (permissions == null || permissions.getPermissions() == null) {
            return Map.of();
        }
        return permissions.getPermissions().entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue() == null || entry.getValue().getMembers() == null ? List.of() : entry.getValue().getMembers()
            ));
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of members")
        private final Integer size;

        @Schema(
            title = "Internal storage URI",
            description = "Ion file of members. Set only when `fetchType` is STORE."
        )
        private final URI uri;

        @Schema(
            title = "First member",
            description = "Set only when `fetchType` is FETCH_ONE."
        )
        private final Member member;

        @Schema(
            title = "Members",
            description = "Set only when `fetchType` is FETCH."
        )
        private final List<Member> members;

        @Schema(
            title = "Permissions by role",
            description = "Role name to member identifiers. Set only when `fetchType` is FETCH, so FETCH_ONE does not repeat the full member list."
        )
        private final Map<String, List<String>> permissions;
    }
}
