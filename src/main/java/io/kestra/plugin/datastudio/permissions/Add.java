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
    description = "Add members to a role on a report or data source. `role` is VIEWER or EDITOR and applies to `user:`, `group:`, `serviceAccount:`, and `domain:` members. `linkRole` is LINK_VIEWER or LINK_EDITOR and applies only to `allUsers` and `domain:` members. One call uses one role, so named members and link members cannot be mixed. `allUsers` and `domain:` require `allowLinkSharing: true`."
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
        description = "Role to grant. VIEWER or EDITOR. OWNER, LINK_VIEWER, and LINK_EDITOR are not accepted here."
    )
    @PluginProperty(group = "source")
    @NotNull
    @Builder.Default
    private Property<AssetRole> role = Property.ofValue(AssetRole.VIEWER);

    @Schema(
        title = "Link role",
        description = "LINK_VIEWER or LINK_EDITOR. Used only when every member is `allUsers` or `domain:`. Requires `allowLinkSharing`. Do not set this when granting `role` to `user:`, `group:`, or `serviceAccount:` members."
    )
    @PluginProperty(group = "source")
    private Property<LinkRole> linkRole;

    @Schema(
        title = "Allow link sharing",
        description = "Permit `allUsers`, `domain:` members, and `linkRole`. Leave false unless the flow should make an asset public or domain-wide."
    )
    @PluginProperty(group = "source")
    @NotNull
    @Builder.Default
    private Property<Boolean> allowLinkSharing = Property.ofValue(false);

    @Schema(
        title = "Members",
        description = "Members to add. `user:`, `group:`, and `serviceAccount:` take `role`. `allUsers` takes `linkRole`. `domain:` takes `role` or `linkRole`. `allUsers` and `domain:` require `allowLinkSharing`."
    )
    @PluginProperty(group = "source")
    @NotNull
    private Property<List<String>> members;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String assetName = runContext.render(this.assetName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("assetName is required"));
        AssetRole renderedRole = runContext.render(this.role).as(AssetRole.class).orElse(AssetRole.VIEWER);
        LinkRole renderedLinkRole = runContext.render(this.linkRole).as(LinkRole.class).orElse(null);
        boolean allowLinkSharing = runContext.render(this.allowLinkSharing).as(Boolean.class).orElse(false);
        List<String> members = runContext.render(this.members).asList(String.class);
        String role = Members.grantRole(renderedRole, renderedLinkRole, allowLinkSharing, members);

        Permissions permissions;
        try (DataStudioClient client = this.open(runContext, true)) {
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
