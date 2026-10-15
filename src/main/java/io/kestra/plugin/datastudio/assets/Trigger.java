package io.kestra.plugin.datastudio.assets;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.datastudio.DataStudioClient;
import io.kestra.plugin.datastudio.DataStudioConnection;
import io.kestra.plugin.datastudio.models.Asset;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Watch Data Studio assets",
    description = "Poll reports or data sources and start an execution when an asset is created or updated after the watermark stored in the namespace KV store. The watermark key includes the flow id and the trigger id, so two flows in the same namespace do not share state. The first poll records the current assets and does not start an execution. A search that exceeds `maxPages` (default 50) fails and does not move the watermark."
)
@Plugin(
    examples = {
        @Example(
            title = "Run a flow when a report is created or updated",
            full = true,
            code = """
                id: on_datastudio_change
                namespace: company.team

                triggers:
                  - id: watch
                    type: io.kestra.plugin.datastudio.assets.Trigger
                    serviceAccount: "{{ secret('GCP_SERVICE_ACCOUNT_JSON') }}"
                    assetTypes:
                      - REPORT
                    interval: PT5M

                tasks:
                  - id: handle_event
                    type: io.kestra.plugin.core.log.Log
                    message: "Event received: {{ trigger.title }}"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output>, DataStudioConnection {

    @Schema(
        title = "Service account JSON",
        description = "Google service account key JSON. The trigger requests `https://www.googleapis.com/auth/datastudio.readonly` through domain-wide delegation in a Google Workspace or Cloud Identity organization. Use this or `accessToken`."
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> serviceAccount;

    @Schema(
        title = "Access token",
        description = "OAuth access token with the `datastudio.readonly` scope. When set, the service account exchange is skipped."
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> accessToken;

    @Schema(
        title = "Delegated user",
        description = "Workspace user email the service account impersonates. Required for domain-wide delegation. Ignored when `accessToken` is set."
    )
    @PluginProperty(group = "connection")
    private Property<String> subject;

    @Schema(
        title = "API base URL",
        description = "Data Studio API root. Defaults to `https://datastudio.googleapis.com/v1`."
    )
    @PluginProperty(group = "connection")
    @NotNull
    @Builder.Default
    private Property<String> baseUrl = Property.ofValue("https://datastudio.googleapis.com/v1");

    @Schema(
        title = "Token URI",
        description = "OAuth token endpoint used to exchange a service account JWT."
    )
    @PluginProperty(group = "connection")
    @NotNull
    @Builder.Default
    private Property<String> tokenUri = Property.ofValue("https://oauth2.googleapis.com/token");

    @Schema(
        title = "Asset types",
        description = "Asset types to watch. Each value is queried separately. Allowed values are REPORT and DATA_SOURCE."
    )
    @PluginProperty(group = "source")
    @NotNull
    @Builder.Default
    private Property<List<String>> assetTypes = Property.ofValue(List.of("REPORT"));

    @Schema(
        title = "Maximum pages",
        description = "Fail when one asset type needs more pages than this. Default 50. The watermark is not moved, and the partial asset list is discarded."
    )
    @PluginProperty(group = "advanced")
    @NotNull
    @Builder.Default
    private Property<Integer> maxPages = Property.ofValue(DataStudioClient.DEFAULT_MAX_PAGES);

    @Schema(
        title = "Interval",
        description = "How often to poll Data Studio. Minimum practical value is PT30S."
    )
    @PluginProperty(group = "execution")
    @NotNull
    @Builder.Default
    private Duration interval = Duration.parse("PT5M");

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        List<String> types = runContext.render(this.assetTypes).asList(String.class);
        int maxPages = runContext.render(this.maxPages).as(Integer.class).orElse(DataStudioClient.DEFAULT_MAX_PAGES);
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be at least 1");
        }
        List<Asset> assets = new ArrayList<>();
        try (DataStudioClient client = DataStudioClient.open(runContext, this, false)) {
            for (String assetType : types) {
                assets.addAll(client.searchAll(assetType, null, null, false, null, null, maxPages));
            }
        }

        String namespace = conditionContext.getFlow().getNamespace();
        KVStore kv = runContext.namespaceKv(namespace);
        String key = stateKey(context);
        Optional<Watermark> previous = read(kv, key);
        Watermark current = Watermark.from(assets);
        if (previous.isEmpty()) {
            write(kv, key, current);
            runContext.logger().info("Stored the initial Data Studio watermark for {} assets", assets.size());
            return Optional.empty();
        }

        List<Asset> changed = assets.stream()
            .filter(asset -> previous.get().isChanged(asset))
            .toList();
        if (changed.isEmpty()) {
            write(kv, key, current);
            return Optional.empty();
        }

        Asset first = changed.getFirst();
        runContext.logger().info("Data Studio asset {} changed", first.getName());
        Output output = Output.builder()
            .title(first.getTitle())
            .name(first.getName())
            .assetType(first.getAssetType())
            .updateTime(first.getUpdateTime())
            .size(changed.size())
            .assets(changed)
            .build();
        Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
        write(kv, key, current);
        return Optional.of(execution);
    }

    private String stateKey(TriggerContext context) {
        String triggerId = context.getTriggerId() != null ? context.getTriggerId() : this.getId();
        return "datastudio_assets_" + segment(context.getFlowId()) + "_" + segment(triggerId);
    }

    private static String segment(String value) {
        String raw = value == null || value.isBlank() ? "unknown" : value;
        String sanitized = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.isEmpty() || !Character.isLetterOrDigit(sanitized.charAt(0))) {
            sanitized = "t" + sanitized;
        }
        return sanitized.length() + "_" + sanitized;
    }

    private Optional<Watermark> read(KVStore kv, String key) throws Exception {
        Optional<KVValue> value = kv.getValue(key);
        if (value.isEmpty() || value.get().value() == null) {
            return Optional.empty();
        }
        Object stored = value.get().value();
        if (stored instanceof String json) {
            return Optional.of(JacksonMapper.ofJson().readValue(json, Watermark.class));
        }
        if (stored instanceof Map<?, ?> map) {
            return Optional.of(JacksonMapper.ofJson().convertValue(map, Watermark.class));
        }
        return Optional.empty();
    }

    private void write(KVStore kv, String key, Watermark watermark) throws Exception {
        String json = JacksonMapper.ofJson().writeValueAsString(watermark);
        kv.put(key, new KVValueAndMetadata(new KVMetadata("Data Studio asset watermark", (Duration) null), json));
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Title of the first changed asset")
        private final String title;

        @Schema(title = "Name of the first changed asset")
        private final String name;

        @Schema(title = "Type of the first changed asset")
        private final String assetType;

        @Schema(title = "Update time of the first changed asset")
        private final String updateTime;

        @Schema(title = "Number of changed assets")
        private final Integer size;

        @Schema(title = "Changed assets")
        private final List<Asset> assets;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Watermark {
        private String updateTime;
        private Set<String> names = new LinkedHashSet<>();

        static Watermark from(List<Asset> assets) {
            Watermark watermark = new Watermark();
            Instant max = null;
            for (Asset asset : assets) {
                if (asset.getName() != null) {
                    watermark.names.add(asset.getName());
                }
                Instant updated = parse(asset.getUpdateTime());
                if (updated != null && (max == null || updated.isAfter(max))) {
                    max = updated;
                }
            }
            watermark.updateTime = max == null ? null : max.toString();
            return watermark;
        }

        boolean isChanged(Asset asset) {
            if (asset.getName() == null || this.names == null || !this.names.contains(asset.getName())) {
                return true;
            }
            Instant updated = parse(asset.getUpdateTime());
            Instant watermark = parse(this.updateTime);
            return updated != null && (watermark == null || updated.isAfter(watermark));
        }

        private static Instant parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return Instant.parse(value);
        }
    }
}
