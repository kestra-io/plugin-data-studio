package io.kestra.plugin.datastudio;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datastudio.models.Asset;
import io.kestra.plugin.datastudio.models.Permissions;
import io.kestra.plugin.datastudio.models.SearchResponse;

/**
 * Stateless Data Studio HTTP client. Create it inside {@code run()} and close it before returning.
 *
 * <p>Data Studio has no Java client in {@code plugin-gcp}, and this plugin is not allowed to add
 * another HTTP stack, so a service account is exchanged with a JWT bearer request through Kestra's
 * {@code HttpClient}. Read tasks request {@code datastudio.readonly}. Only grant and revoke request
 * {@code datastudio}.
 */
public final class DataStudioClient implements AutoCloseable {
    private static final ObjectMapper JSON = JacksonMapper.ofJson().copy()
        .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

    public static final String READ_SCOPE = "https://www.googleapis.com/auth/datastudio.readonly";
    public static final String WRITE_SCOPE = "https://www.googleapis.com/auth/datastudio";
    public static final int DEFAULT_MAX_PAGES = 50;

    private final RunContext runContext;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String accessToken;

    private DataStudioClient(RunContext runContext, HttpClient httpClient, String baseUrl, String accessToken) {
        this.runContext = runContext;
        this.httpClient = httpClient;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.accessToken = accessToken;
    }

    public static DataStudioClient open(RunContext runContext, DataStudioConnection connection) throws Exception {
        return open(runContext, connection, false);
    }

    public static DataStudioClient open(RunContext runContext, DataStudioConnection connection, boolean write) throws Exception {
        var rBaseUrl = runContext.render(connection.getBaseUrl()).as(String.class)
            .orElse("https://datastudio.googleapis.com/v1");
        var rTokenUri = runContext.render(connection.getTokenUri()).as(String.class)
            .orElse("https://oauth2.googleapis.com/token");
        var rAccessToken = blankToNull(runContext.render(connection.getAccessToken()).as(String.class).orElse(null));
        var rServiceAccount = blankToNull(runContext.render(connection.getServiceAccount()).as(String.class).orElse(null));
        var rSubject = blankToNull(runContext.render(connection.getSubject()).as(String.class).orElse(null));

        HttpClient httpClient = new HttpClient(runContext, HttpConfiguration.builder()
            .timeout(TimeoutConfiguration.builder()
                .connectTimeout(Property.ofValue(Duration.ofSeconds(30)))
                .readIdleTimeout(Property.ofValue(Duration.ofMinutes(2)))
                .build())
            .build());
        try {
            String token = rAccessToken != null
                ? rAccessToken
                : exchangeServiceAccount(runContext, httpClient, rServiceAccount, rSubject, rTokenUri, write ? WRITE_SCOPE : READ_SCOPE);
            return new DataStudioClient(runContext, httpClient, rBaseUrl, token);
        } catch (Exception e) {
            httpClient.close();
            throw e;
        }
    }

    public List<Asset> searchAll(String assetType, String title, String owner, Boolean includeTrashed, String orderBy, Integer pageSize, int maxPages) throws Exception {
        List<Asset> all = new ArrayList<>();
        consumeSearch(assetType, title, owner, includeTrashed, orderBy, pageSize, maxPages, all::add);
        return all;
    }

    public Asset findByName(String assetType, String assetName, boolean includeTrashed, int maxPages) throws Exception {
        String pageToken = null;
        for (int page = 0; page < maxPages; page++) {
            SearchResponse response = search(assetType, null, null, includeTrashed, null, null, pageToken);
            if (response.getAssets() != null) {
                for (Asset asset : response.getAssets()) {
                    if (assetName.equals(asset.getName())) {
                        return asset;
                    }
                }
            }
            String next = response.getNextPageToken();
            if (next == null || next.isBlank() || next.equals(pageToken)) {
                return null;
            }
            pageToken = next;
        }
        throw truncated(maxPages);
    }

    public void consumeSearch(String assetType, String title, String owner, Boolean includeTrashed, String orderBy, Integer pageSize, int maxPages, AssetConsumer consumer) throws Exception {
        String pageToken = null;
        for (int page = 0; page < maxPages; page++) {
            SearchResponse response = search(assetType, title, owner, includeTrashed, orderBy, pageSize, pageToken);
            if (response.getAssets() != null) {
                for (Asset asset : response.getAssets()) {
                    consumer.accept(asset);
                }
            }
            String next = response.getNextPageToken();
            if (next == null || next.isBlank() || next.equals(pageToken)) {
                return;
            }
            pageToken = next;
        }
        throw truncated(maxPages);
    }

    @FunctionalInterface
    public interface AssetConsumer {
        void accept(Asset asset) throws Exception;
    }

    private static IOException truncated(int maxPages) {
        return new IOException("Data Studio search exceeded " + maxPages + " pages. Narrow the query. A partial result is not returned and a trigger watermark is not moved.");
    }

    public SearchResponse search(String assetType, String title, String owner, Boolean includeTrashed, String orderBy, Integer pageSize, String pageToken) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("assetTypes", assetType);
        query.put("title", title);
        query.put("owner", owner);
        if (includeTrashed != null) {
            query.put("includeTrashed", Boolean.toString(includeTrashed));
        }
        query.put("orderBy", orderBy);
        if (pageSize != null) {
            query.put("pageSize", Integer.toString(pageSize));
        }
        query.put("pageToken", pageToken);
        return get(this.baseUrl + "/assets:search" + toQuery(query), SearchResponse.class);
    }

    public Permissions getPermissions(String assetName) throws Exception {
        return get(this.baseUrl + "/assets/" + encode(assetName) + "/permissions", Permissions.class);
    }

    public Permissions addMembers(String assetName, String role, List<String> members) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", role);
        body.put("members", members);
        return post(this.baseUrl + "/assets/" + encode(assetName) + "/permissions:addMembers", body, Permissions.class);
    }

    public Permissions revokeMembers(String assetName, List<String> members) throws Exception {
        return post(this.baseUrl + "/assets/" + encode(assetName) + "/permissions:revokeAllPermissions", Map.of("members", members), Permissions.class);
    }

    @Override
    public void close() throws IOException {
        this.httpClient.close();
    }

    private <T> T get(String url, Class<T> type) throws Exception {
        HttpRequest request = HttpRequest.builder()
            .uri(URI.create(url))
            .method("GET")
            .addHeader("Authorization", "Bearer " + this.accessToken)
            .addHeader("Accept", "application/json")
            .build();
        return send(request, type);
    }

    private <T> T post(String url, Object body, Class<T> type) throws Exception {
        HttpRequest request = HttpRequest.builder()
            .uri(URI.create(url))
            .method("POST")
            .addHeader("Authorization", "Bearer " + this.accessToken)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .body(HttpRequest.StringRequestBody.builder()
                .content(JacksonMapper.ofJson().writeValueAsString(body))
                .contentType("application/json")
                .build())
            .build();
        return send(request, type);
    }

    private <T> T send(HttpRequest request, Class<T> type) throws Exception {
        runContext.logger().debug("Data Studio {} {}", request.getMethod(), request.getUri());
        HttpResponse<String> response;
        try {
            response = this.httpClient.request(request, String.class);
        } catch (HttpClientResponseException e) {
            throw apiError(e, "Data Studio API");
        }
        int code = response.getStatus().getCode();
        String body = response.getBody() == null ? "" : response.getBody();
        if (code >= 400) {
            throw new IOException("Data Studio API returned HTTP " + code + ": " + truncate(body));
        }
        if (body.isBlank()) {
            return type.getDeclaredConstructor().newInstance();
        }
        return JSON.readValue(body, type);
    }

    private static String exchangeServiceAccount(RunContext runContext, HttpClient httpClient, String serviceAccount, String subject, String tokenUri, String scope) throws Exception {
        if (serviceAccount == null) {
            throw new IllegalArgumentException("Set serviceAccount or accessToken");
        }
        Map<String, Object> key = JacksonMapper.ofJson().readValue(serviceAccount, new TypeReference<>() {
        });
        String clientEmail = stringClaim(key, "client_email");
        String privateKeyPem = stringClaim(key, "private_key");
        if (clientEmail == null || privateKeyPem == null) {
            throw new IllegalArgumentException("Service account JSON must contain client_email and private_key");
        }

        String assertion = signJwt(clientEmail, privateKeyPem, subject, tokenUri, scope);
        String form = "grant_type=" + encode("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + encode(assertion);
        HttpRequest request = HttpRequest.builder()
            .uri(URI.create(tokenUri))
            .method("POST")
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .body(HttpRequest.StringRequestBody.builder()
                .content(form)
                .contentType("application/x-www-form-urlencoded")
                .build())
            .build();
        runContext.logger().debug("Requesting a Data Studio access token for {}", clientEmail);
        HttpResponse<String> response;
        try {
            response = httpClient.request(request, String.class);
        } catch (HttpClientResponseException e) {
            throw apiError(e, "Token endpoint");
        }
        if (response.getStatus().getCode() >= 400) {
            throw new IOException("Token endpoint returned HTTP " + response.getStatus().getCode() + ": " + truncate(response.getBody()));
        }
        if (response.getBody() == null || response.getBody().isBlank()) {
            throw new IOException("Token endpoint returned an empty body");
        }
        Map<String, Object> token = JacksonMapper.ofJson().readValue(response.getBody(), new TypeReference<>() {
        });
        String accessToken = stringClaim(token, "access_token");
        if (accessToken == null) {
            throw new IOException("Token endpoint response did not contain access_token");
        }
        return accessToken;
    }

    private static String signJwt(String clientEmail, String privateKeyPem, String subject, String tokenUri, String scope) throws Exception {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientEmail);
        claims.put("scope", scope);
        claims.put("aud", tokenUri);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(3600).getEpochSecond());
        if (subject != null) {
            claims.put("sub", subject);
        }
        String header = base64Url(JacksonMapper.ofJson().writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payload = base64Url(JacksonMapper.ofJson().writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(readPrivateKey(privateKeyPem));
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(signature.sign());
    }

    private static PrivateKey readPrivateKey(String pem) throws Exception {
        String base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(decoded));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String toQuery(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        params.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            builder.append(builder.isEmpty() ? '?' : '&');
            builder.append(encode(key)).append('=').append(encode(value));
        });
        return builder.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://datastudio.googleapis.com/v1";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String stringClaim(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private static IOException apiError(HttpClientResponseException exception, String label) {
        HttpResponse<?> response = exception.getResponse();
        int code = response == null || response.getStatus() == null ? 0 : response.getStatus().getCode();
        return new IOException(label + " returned HTTP " + code + ": " + truncate(bodyText(response)), exception);
    }

    private static String bodyText(HttpResponse<?> response) {
        if (response == null || response.getBody() == null) {
            return "";
        }
        Object body = response.getBody();
        if (body instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return body.toString();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500);
    }
}
