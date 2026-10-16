package io.kestra.plugin.datastudio.models;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum AssetType {
    REPORT,
    DATA_SOURCE,
    @JsonEnumDefaultValue
    UNKNOWN
}
