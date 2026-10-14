package io.kestra.plugin.datastudio.permissions;

import java.util.List;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datastudio.AbstractDataStudioTask;
import io.kestra.plugin.datastudio.DataStudioClient;
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
    title = "Revoke Data Studio access",
    description = "Remove members from a report or data source. Owners and the calling user cannot be removed. Unknown members do not fail the request."
)
@Plugin(
    examples = {
        @Example(
            title = "Revoke a user's access to a report",
            full = true,
            code = """
                id: permissions_revoke
                namespace: company.team

                inputs:
                  - id: asset_id
                    type: STRING

                tasks:
                  - id: revoke
                    type: io.kestra.plugin.datastudio.permissions.Revoke
                    serviceAccount: "{{ secret('GCP_SERVICE_ACCOUNT_JSON') }}"
                    assetName: "{{ inputs.asset_id }}"
                    members:
                      - "user:former.analyst@acme.com"
                """
        )
    }
)
public class Revoke extends AbstractDataStudioTask implements RunnableTask<Revoke.Output> {

    @Schema(title = "Asset name", description = "The Data Studio asset ID.")
    @PluginProperty(group = "source")
    @NotNull
    private Property<String> assetName;

    @Schema(
        title = "Members",
        description = "Members to remove. Prefix each entry with `user:`, `group:`, or `domain:`."
    )
    @PluginProperty(group = "source")
    @NotNull
    private Property<List<String>> members;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String assetName = runContext.render(this.assetName).as(String.class).orElseThrow();
        List<String> members = runContext.render(this.members).asList(String.class);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("members must not be empty");
        }

        Permissions permissions;
        try (DataStudioClient client = this.open(runContext)) {
            runContext.logger().info("Revoking access on Data Studio asset {} for {}", assetName, members);
            permissions = client.revokeMembers(assetName, members);
        }
        return Output.builder()
            .permissions(Get.roleMap(permissions))
            .size(Get.flatten(permissions).size())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Updated permissions by role")
        private final Map<String, List<String>> permissions;

        @Schema(title = "Number of members on the asset after the update")
        private final Integer size;
    }
}
