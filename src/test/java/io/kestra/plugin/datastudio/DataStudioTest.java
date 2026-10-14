package io.kestra.plugin.datastudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.datastudio.assets.Get;
import io.kestra.plugin.datastudio.assets.Search;
import io.kestra.plugin.datastudio.assets.Trigger;
import io.kestra.plugin.datastudio.permissions.Add;
import io.kestra.plugin.datastudio.permissions.Revoke;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@MicronautTest
class DataStudioTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void searchGetPermissionsAndStore() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales","owner":"ada@acme.com","assetType":"REPORT","updateTime":"2026-04-01T00:00:00Z"}],"nextPageToken":""}
                """;
            api.permissionsBody = """
                {"permissions":{"VIEWER":{"members":["group:analysts@acme.com"]},"OWNER":{"members":["user:ada@acme.com"]}}}
                """;

            Search search = Search.builder()
                .id("search")
                .type(Search.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetTypes(Property.ofValue(List.of("REPORT")))
                .owner(Property.ofValue("ada@acme.com"))
                .fetchType(Property.ofValue(FetchType.STORE))
                .build();
            RunContext runContext = TestsUtils.mockRunContext(this.runContextFactory, search, Map.of());
            Search.Output stored = search.run(runContext);

            assertEquals(1, stored.getSize());
            assertNotNull(stored.getUri());
            assertTrue(api.lastQuery.contains("assetTypes=REPORT"));
            assertTrue(api.lastQuery.contains("owner=ada%40acme.com"));
            List<Object> rows = new ArrayList<>();
            try (InputStream input = runContext.storage().getFile(stored.getUri())) {
                FileSerde.read(input, rows::add);
            }
            assertEquals(1, rows.size());

            Get get = Get.builder()
                .id("get")
                .type(Get.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .assetTypes(Property.ofValue(List.of("REPORT")))
                .build();
            Get.Output got = get.run(TestsUtils.mockRunContext(this.runContextFactory, get, Map.of()));
            assertEquals("Sales", got.getAsset().getTitle());

            io.kestra.plugin.datastudio.permissions.Get permissions = io.kestra.plugin.datastudio.permissions.Get.builder()
                .id("permissions")
                .type(io.kestra.plugin.datastudio.permissions.Get.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .build();
            var listed = permissions.run(TestsUtils.mockRunContext(this.runContextFactory, permissions, Map.of()));
            assertEquals(2, listed.getSize());
            assertTrue(listed.getMembers().stream().anyMatch(member -> "group:analysts@acme.com".equals(member.getMember())));

            Add add = Add.builder()
                .id("add")
                .type(Add.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .role(Property.ofValue("VIEWER"))
                .members(Property.ofValue(List.of("group:analysts@acme.com")))
                .build();
            add.run(TestsUtils.mockRunContext(this.runContextFactory, add, Map.of()));
            assertEquals("POST", api.lastMethod);
            assertTrue(api.lastPath.contains("permissions:addMembers"));
            assertTrue(api.lastBody.contains("VIEWER"));

            Revoke revoke = Revoke.builder()
                .id("revoke")
                .type(Revoke.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .members(Property.ofValue(List.of("user:former.analyst@acme.com")))
                .build();
            revoke.run(TestsUtils.mockRunContext(this.runContextFactory, revoke, Map.of()));
            assertTrue(api.lastPath.contains("permissions:revokeAllPermissions"));
            assertTrue(api.lastBody.contains("former.analyst@acme.com"));
        }
    }

    @Test
    void triggerKeepsWatermarkInNamespaceKv() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales","assetType":"REPORT","updateTime":"2026-04-01T00:00:00Z"}]}
                """;
            Trigger trigger = Trigger.builder()
                .id("watch")
                .type(Trigger.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetTypes(Property.ofValue(List.of("REPORT")))
                .interval(Duration.ofMinutes(5))
                .build();

            var first = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            assertTrue(trigger.evaluate(first.getKey(), first.getValue()).isEmpty());

            var second = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            assertTrue(trigger.evaluate(second.getKey(), second.getValue()).isEmpty());

            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales Q2","assetType":"REPORT","updateTime":"2026-04-02T00:00:00Z"}]}
                """;
            var third = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            var execution = trigger.evaluate(third.getKey(), third.getValue());
            assertTrue(execution.isPresent());
            assertEquals("Sales Q2", execution.get().getTrigger().getVariables().get("title"));
        }
    }

    @Test
    void serviceAccountJwtIsSigned() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        String serviceAccount = JacksonMapper.ofJson().writeValueAsString(Map.of(
            "client_email", "studio@project.iam.gserviceaccount.com",
            "private_key", pem
        ));

        AtomicReference<String> assertion = new AtomicReference<>();
        try (FakeApi api = FakeApi.start()) {
            api.onToken = body -> {
                for (String part : body.split("&")) {
                    if (part.startsWith("assertion=")) {
                        assertion.set(java.net.URLDecoder.decode(part.substring("assertion=".length()), StandardCharsets.UTF_8));
                    }
                }
            };
            Search search = Search.builder()
                .id("search")
                .type(Search.class.getName())
                .serviceAccount(Property.ofValue(serviceAccount))
                .subject(Property.ofValue("analyst@acme.com"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .tokenUri(Property.ofValue(api.tokenUri()))
                .assetTypes(Property.ofValue(List.of("DATA_SOURCE")))
                .fetchType(Property.ofValue(FetchType.NONE))
                .build();
            api.searchBody = """
                {"assets":[]}
                """;
            Search.Output output = search.run(TestsUtils.mockRunContext(this.runContextFactory, search, Map.of()));
            assertEquals(0, output.getSize());
            assertEquals("Bearer ya29.test", api.lastAuthorization);
        }

        String[] parts = assertion.get().split("\\.");
        assertEquals(3, parts.length);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(payload.contains("studio@project.iam.gserviceaccount.com"));
        assertTrue(payload.contains(DataStudioClient.SCOPE));
        assertTrue(payload.contains("analyst@acme.com"));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(pair.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    @Test
    void ownerRoleCannotBeGranted() {
        Add add = Add.builder()
            .id("add")
            .type(Add.class.getName())
            .accessToken(Property.ofValue("token-1"))
            .assetName(Property.ofValue("report-1"))
            .role(Property.ofValue("OWNER"))
            .members(Property.ofValue(List.of("user:ada@acme.com")))
            .build();
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> add.run(TestsUtils.mockRunContext(this.runContextFactory, add, Map.of()))
        );
        assertFalse(error.getMessage().isBlank());
    }

    private static final class FakeApi implements AutoCloseable {
        private final HttpServer server;
        private String searchBody = """
            {"assets":[]}
            """;
        private String permissionsBody = """
            {"permissions":{}}
            """;
        private String lastMethod;
        private String lastPath;
        private String lastQuery;
        private String lastBody;
        private String lastAuthorization;
        private java.util.function.Consumer<String> onToken = body -> {
        };

        private FakeApi(HttpServer server) {
            this.server = server;
        }

        static FakeApi start() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeApi api = new FakeApi(server);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String query = exchange.getRequestURI().getRawQuery();
                byte[] request = exchange.getRequestBody().readAllBytes();
                api.lastMethod = exchange.getRequestMethod();
                api.lastPath = path;
                api.lastQuery = query;
                api.lastBody = new String(request, StandardCharsets.UTF_8);
                api.lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] response;
                if ("/token".equals(path)) {
                    api.onToken.accept(api.lastBody);
                    response = """
                        {"access_token":"ya29.test","token_type":"Bearer","expires_in":3600}
                        """.getBytes(StandardCharsets.UTF_8);
                } else if (path.endsWith("/permissions") || path.contains("permissions:")) {
                    response = api.permissionsBody.getBytes(StandardCharsets.UTF_8);
                } else {
                    response = api.searchBody.getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            server.start();
            return api;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
        }

        String tokenUri() {
            return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/token";
        }

        @Override
        public void close() {
            this.server.stop(0);
        }
    }
}
