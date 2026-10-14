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
    title = "Grant Data Studio access",
    description = "Add members to a role on a report or data source. The OWNER role cannot be granted through this API."
)
@Plugin(
    examples = {
        @Example(
            title = "Grant a group read access to a report",
            full = true,
            code = """
                id: permissions_add
                namespace: company.team

                inputs:
                  - id: asset_id
                    type: STRING

                tasks:
                  - id: add
                    type: io.kestra.plugin.datastudio.permissions.Add
                    serviceAccount: "{{ secret('GCP_SERVICE_ACCOUNT_JSON') }}"
                    assetName: "{{ inputs.asset_id }}"
                    members:
                      - "group:analysts@acme.com"
                    role: VIEWER
                """
        )
    }
)
public class Add extends AbstractDataStudioTask implements RunnableTask<Add.Output> {

    @Schema(title = "Asset name", description = "The Data Studio asset ID.")
    @PluginProperty(group = "source")
    @NotNull
    private Property<String> assetName;

    @Schema(
        title = "Role",
        description = "Role to grant. VIEWER or EDITOR. Members cannot be added to OWNER."
    )
    @PluginProperty(group = "source")
    @NotNull
    private Property<String> role;

    @Schema(
        title = "Members",
        description = "Members to add. Prefix each entry with `user:`, `group:`, or `domain:`."
    )
    @PluginProperty(group = "source")
    @NotNull
    private Property<List<String>> members;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String assetName = runContext.render(this.assetName).as(String.class).orElseThrow();
        String role = runContext.render(this.role).as(String.class).orElseThrow();
        if ("OWNER".equalsIgnoreCase(role)) {
            throw new IllegalArgumentException("Members cannot be added to the OWNER role");
        }
        List<String> members = runContext.render(this.members).asList(String.class);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("members must not be empty");
        }

        Permissions permissions;
        try (DataStudioClient client = this.open(runContext)) {
            runContext.logger().info("Granting {} on Data Studio asset {} to {}", role, assetName, members);
            permissions = client.addMembers(assetName, role, members);
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
