package io.kestra.plugin.datastudio.models;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Asset {

    @Schema(title = "Asset name", description = "The Data Studio asset ID.")
    private String name;

    @Schema(title = "Title")
    private String title;

    @Schema(title = "Description")
    private String description;

    @Schema(title = "Owner email")
    private String owner;

    @Schema(title = "Creator email")
    private String creator;

    @Schema(title = "Creation time")
    private Instant createTime;

    @Schema(title = "Update time")
    private Instant updateTime;

    @Schema(title = "Asset type", description = "REPORT or DATA_SOURCE. An unexpected API value is UNKNOWN, so one asset does not fail the whole response.")
    private AssetType assetType;

    @Schema(title = "Trashed", description = "True when the asset is in the trash.")
    private Boolean trashed;
}
