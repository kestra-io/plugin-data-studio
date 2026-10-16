package io.kestra.plugin.datastudio.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum AssetType {
    REPORT,
    DATA_SOURCE,
    @JsonEnumDefaultValue
    UNKNOWN;

    public static void rejectUnknownRequest(List<AssetType> assetTypes) {
        if (assetTypes != null && assetTypes.contains(UNKNOWN)) {
            throw new IllegalArgumentException("assetTypes accepts REPORT and DATA_SOURCE. UNKNOWN is only used when the API returns an unexpected asset type.");
        }
    }
}
