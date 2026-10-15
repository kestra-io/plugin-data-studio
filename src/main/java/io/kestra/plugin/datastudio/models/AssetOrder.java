package io.kestra.plugin.datastudio.models;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AssetOrder {
    TITLE("title"),
    LAST_VIEWED_BY_ME("last_viewed_by_me"),
    CREATE_TIME("create_time"),
    LAST_ACCESSED_TIME("last_accessed_time"),
    ID("id");

    private final String apiValue;
}
