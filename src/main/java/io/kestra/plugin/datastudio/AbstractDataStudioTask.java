package io.kestra.plugin.datastudio;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
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
public abstract class AbstractDataStudioTask extends Task implements DataStudioConnection {

    @Schema(
        title = "Service account JSON",
        description = "Google service account key JSON. Read tasks request `https://www.googleapis.com/auth/datastudio.readonly`. Add and Revoke request `https://www.googleapis.com/auth/datastudio`. Grant the scope through domain-wide delegation in a Google Workspace or Cloud Identity organization. Use this or `accessToken`."
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> serviceAccount;

    @Schema(
        title = "Access token",
        description = "OAuth access token. When set, the service account exchange is skipped, so the token must already include `datastudio.readonly` for read tasks or `datastudio` for Add and Revoke."
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> accessToken;

    @Schema(
        title = "Delegated user",
        description = "Workspace user email the service account impersonates. Required for domain-wide delegation. Ignored when `accessToken` is set."
    )
    @PluginProperty(group = "connection")
    protected Property<String> subject;

    @Schema(
        title = "API base URL",
        description = "Data Studio API root. Defaults to `https://datastudio.googleapis.com/v1`."
    )
    @PluginProperty(group = "connection")
    @NotNull
    @Builder.Default
    protected Property<String> baseUrl = Property.ofValue("https://datastudio.googleapis.com/v1");

    @Schema(
        title = "Token URI",
        description = "OAuth token endpoint used to exchange a service account JWT."
    )
    @PluginProperty(group = "connection")
    @NotNull
    @Builder.Default
    protected Property<String> tokenUri = Property.ofValue("https://oauth2.googleapis.com/token");

    /**
     * Opens a client for this run. Callers must close it before returning.
     */
    protected DataStudioClient open(RunContext runContext) throws Exception {
        return DataStudioClient.open(runContext, this, false);
    }

    protected DataStudioClient open(RunContext runContext, boolean write) throws Exception {
        return DataStudioClient.open(runContext, this, write);
    }
}
