package io.kestra.plugin.datastudio;

import io.kestra.core.models.property.Property;

public interface DataStudioConnection {

    Property<String> getServiceAccount();

    Property<String> getAccessToken();

    Property<String> getSubject();

    Property<String> getBaseUrl();

    Property<String> getTokenUri();
}
