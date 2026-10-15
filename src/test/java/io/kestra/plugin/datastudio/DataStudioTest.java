package io.kestra.plugin.datastudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.datastudio.assets.Get;
import io.kestra.plugin.datastudio.assets.Search;
import io.kestra.plugin.datastudio.assets.Trigger;
import io.kestra.plugin.datastudio.permissions.Add;
import io.kestra.plugin.datastudio.permissions.AssetRole;
import io.kestra.plugin.datastudio.permissions.LinkRole;
import io.kestra.plugin.datastudio.models.AssetType;
import io.kestra.plugin.datastudio.permissions.Revoke;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@KestraTest(startRunner = false, startWorker = false)
class DataStudioTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void searchStoresIon() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales","owner":"ada@acme.com","assetType":"REPORT","updateTime":"2026-04-01T00:00:00Z"}],"nextPageToken":""}
                """;
            Search search = Search.builder()
                .id(taskId())
                .type(Search.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetTypes(Property.ofValue(List.of(AssetType.REPORT)))
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
        }
    }

    @Test
    void getFindsAsset() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales","owner":"ada@acme.com","assetType":"REPORT","updateTime":"2026-04-01T00:00:00Z"}],"nextPageToken":""}
                """;
            Get get = Get.builder()
                .id(taskId())
                .type(Get.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .assetTypes(Property.ofValue(List.of(AssetType.REPORT)))
                .build();
            Get.Output got = get.run(TestsUtils.mockRunContext(this.runContextFactory, get, Map.of()));
            assertEquals("Sales", got.getAsset().getTitle());
            assertEquals(AssetType.REPORT, got.getAsset().getAssetType());
        }
    }

    @Test
    void permissionsGetListsMembers() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.permissionsBody = """
                {"permissions":{"VIEWER":{"members":["group:analysts@acme.com"]},"OWNER":{"members":["user:ada@acme.com"]}}}
                """;
            io.kestra.plugin.datastudio.permissions.Get permissions = io.kestra.plugin.datastudio.permissions.Get.builder()
                .id(taskId())
                .type(io.kestra.plugin.datastudio.permissions.Get.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .build();
            var listed = permissions.run(TestsUtils.mockRunContext(this.runContextFactory, permissions, Map.of()));
            assertEquals(2, listed.getSize());
            assertTrue(listed.getMembers().stream().anyMatch(member -> "group:analysts@acme.com".equals(member.getMember())));
        }
    }

    @Test
    void addGrantsRole() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.permissionsBody = """
                {"permissions":{"VIEWER":{"members":["group:analysts@acme.com"]},"OWNER":{"members":["user:ada@acme.com"]}}}
                """;
            Add add = Add.builder()
                .id(taskId())
                .type(Add.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .role(Property.ofValue(AssetRole.VIEWER))
                .members(Property.ofValue(List.of("group:analysts@acme.com")))
                .build();
            Add.Output added = add.run(TestsUtils.mockRunContext(this.runContextFactory, add, Map.of()));
            assertEquals("POST", api.lastMethod);
            assertTrue(api.lastPath.contains("permissions:addMembers"));
            assertTrue(api.lastBody.contains("VIEWER"));
            assertEquals(2, added.getSize());
            assertTrue(added.getPermissions().get("VIEWER").contains("group:analysts@acme.com"));
        }
    }

    @Test
    void revokeRemovesMember() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.permissionsBody = """
                {"permissions":{"VIEWER":{"members":["group:analysts@acme.com"]},"OWNER":{"members":["user:ada@acme.com"]}}}
                """;
            Revoke revoke = Revoke.builder()
                .id(taskId())
                .type(Revoke.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .members(Property.ofValue(List.of("user:former.analyst@acme.com")))
                .build();
            Revoke.Output revoked = revoke.run(TestsUtils.mockRunContext(this.runContextFactory, revoke, Map.of()));
            assertTrue(api.lastPath.contains("permissions:revokeAllPermissions"));
            assertTrue(api.lastBody.contains("former.analyst@acme.com"));
            assertEquals(2, revoked.getSize());
        }
    }

    @Test
    void includeTrashedSearchesLiveAssetsBeforeTheTrash() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.searchBody = """
                {"assets":[]}
                """;
            api.trashedBody = """
                {"assets":[{"name":"report-trash","title":"Old","assetType":"REPORT","updateTime":"2026-04-01T00:00:00Z"}]}
                """;
            Get hidden = Get.builder()
                .id(taskId())
                .type(Get.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-trash"))
                .assetTypes(Property.ofValue(List.of(AssetType.REPORT)))
                .includeTrashed(Property.ofValue(false))
                .build();
            assertThrows(IllegalArgumentException.class, () -> hidden.run(TestsUtils.mockRunContext(this.runContextFactory, hidden, Map.of())));
            assertFalse(api.queries.stream().anyMatch(query -> query != null && query.contains("includeTrashed=true")));

            Get found = Get.builder()
                .id(taskId())
                .type(Get.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofExpression("{{ inputs.asset_id }}"))
                .assetTypes(Property.ofExpression("{{ inputs.types }}"))
                .includeTrashed(Property.ofValue(true))
                .build();
            Get.Output output = found.run(TestsUtils.mockRunContext(
                this.runContextFactory,
                found,
                Map.of("asset_id", "report-trash", "types", List.of("REPORT"))
            ));
            assertEquals("Old", output.getAsset().getTitle());
            assertTrue(api.queries.stream().anyMatch(query -> query != null && query.contains("includeTrashed=true")));
        }
    }

    @Test
    void grantErrorListsLinkMembersWhenSharingIsAllowed() {
        Add add = Add.builder()
            .id(taskId())
            .type(Add.class.getName())
            .accessToken(Property.ofValue("token-1"))
            .assetName(Property.ofValue("report-1"))
            .allowLinkSharing(Property.ofValue(true))
            .members(Property.ofValue(List.of("acme.com")))
            .build();
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> add.run(TestsUtils.mockRunContext(this.runContextFactory, add, Map.of()))
        );
        assertTrue(error.getMessage().contains("domain:"));
        assertTrue(error.getMessage().contains("allUsers"));
    }

    @Test
    void fetchOnePermissionsDoNotRepeatTheFullRoleMap() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.permissionsBody = """
                {"permissions":{"VIEWER":{"members":["user:ada@acme.com","group:analysts@acme.com"]}}}
                """;
            io.kestra.plugin.datastudio.permissions.Get permissions = io.kestra.plugin.datastudio.permissions.Get.builder()
                .id(taskId())
                .type(io.kestra.plugin.datastudio.permissions.Get.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .fetchType(Property.ofValue(FetchType.FETCH_ONE))
                .build();
            var listed = permissions.run(TestsUtils.mockRunContext(this.runContextFactory, permissions, Map.of()));
            assertEquals("user:ada@acme.com", listed.getMember().getMember());
            assertNull(listed.getPermissions());
            assertNull(listed.getMembers());
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
                .assetTypes(Property.ofValue(List.of(AssetType.REPORT)))
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
                .id(taskId())
                .type(Search.class.getName())
                .serviceAccount(Property.ofValue(serviceAccount))
                .subject(Property.ofValue("analyst@acme.com"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .tokenUri(Property.ofValue(api.tokenUri()))
                .assetTypes(Property.ofValue(List.of(AssetType.DATA_SOURCE)))
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
        assertTrue(payload.contains("\"scope\":\"" + DataStudioClient.READ_SCOPE + "\""));
        assertTrue(payload.contains("analyst@acme.com"));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(pair.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    @Test
    void fetchAndFetchOneReturnRows() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales","assetType":"REPORT"},{"name":"report-2","title":"Costs","assetType":"REPORT"}]}
                """;
            Search fetch = search(api, FetchType.FETCH, null);
            Search.Output fetched = fetch.run(TestsUtils.mockRunContext(this.runContextFactory, fetch, Map.of()));
            assertEquals(2, fetched.getSize());
            assertEquals(2, fetched.getAssets().size());

            Search one = search(api, FetchType.FETCH_ONE, null);
            Search.Output first = one.run(TestsUtils.mockRunContext(this.runContextFactory, one, Map.of()));
            assertEquals("Sales", first.getAsset().getTitle());
            assertEquals(null, first.getAssets());
        }
    }

    @Test
    void searchReadsEveryPage() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.searchPages = List.of(
                """
                {"assets":[{"name":"report-1","title":"Sales","assetType":"REPORT"}],"nextPageToken":"page-2"}
                """,
                """
                {"assets":[{"name":"report-2","title":"Costs","assetType":"REPORT"}],"nextPageToken":""}
                """
            );
            Search task = search(api, FetchType.FETCH, null);
            Search.Output output = task.run(TestsUtils.mockRunContext(this.runContextFactory, task, Map.of()));
            assertEquals(2, output.getSize());
            assertTrue(api.queries.stream().anyMatch(query -> query != null && query.contains("pageToken=page-2")));
        }
    }

    @Test
    void searchFailsInsteadOfReturningAPartialPageList() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.endlessPages = true;
            Search task = search(api, FetchType.FETCH, 2);
            IOException error = assertThrows(IOException.class, () -> task.run(TestsUtils.mockRunContext(this.runContextFactory, task, Map.of())));
            assertTrue(error.getMessage().contains("exceeded 2 pages"));
        }
    }

    @Test
    void apiErrorsFailTheTask() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.status = 403;
            Search task = search(api);
            IOException error = assertThrows(IOException.class, () -> task.run(TestsUtils.mockRunContext(this.runContextFactory, task, Map.of())));
            assertTrue(error.getMessage().contains("Data Studio API"));
            assertTrue(error.getMessage().contains("403"));
            assertTrue(error.getMessage().contains("denied"));
            assertFalse(error.getMessage().contains("[B@"));
        }
    }

    @Test
    void tokenErrorsFailTheTask() throws Exception {
        KeyPair pair = rsa();
        String serviceAccount = serviceAccount(pair);
        try (FakeApi api = FakeApi.start()) {
            api.tokenStatus = 401;
            Search task = Search.builder()
                .id(taskId())
                .type(Search.class.getName())
                .serviceAccount(Property.ofValue(serviceAccount))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .tokenUri(Property.ofValue(api.tokenUri()))
                .assetTypes(Property.ofValue(List.of(AssetType.REPORT)))
                .build();
            IOException error = assertThrows(IOException.class, () -> task.run(TestsUtils.mockRunContext(this.runContextFactory, task, Map.of())));
            assertTrue(error.getMessage().contains("Token endpoint"));
            assertTrue(error.getMessage().contains("401"));
            assertTrue(error.getMessage().contains("unauthorized"));
            assertFalse(error.getMessage().contains("Data Studio API"));
            assertFalse(error.getMessage().contains("[B@"));
        }
    }

    @Test
    void addRequestsTheWriteScope() throws Exception {
        KeyPair pair = rsa();
        AtomicReference<String> assertion = new AtomicReference<>();
        try (FakeApi api = FakeApi.start()) {
            api.onToken = body -> assertion.set(assertionOf(body));
            Add add = Add.builder()
                .id(taskId())
                .type(Add.class.getName())
                .serviceAccount(Property.ofValue(serviceAccount(pair)))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .tokenUri(Property.ofValue(api.tokenUri()))
                .assetName(Property.ofValue("report-1"))
                .role(Property.ofValue(AssetRole.EDITOR))
                .members(Property.ofValue(List.of("user:ada@acme.com")))
                .build();
            add.run(TestsUtils.mockRunContext(this.runContextFactory, add, Map.of()));
        }
        String payload = jwtPayload(assertion.get());
        assertTrue(payload.contains("\"scope\":\"" + DataStudioClient.WRITE_SCOPE + "\""));
        assertFalse(payload.contains("readonly"));
    }

    @Test
    void linkSharingRequiresAnOptIn() {
        Add blocked = Add.builder()
            .id(taskId())
            .type(Add.class.getName())
            .accessToken(Property.ofValue("token-1"))
            .assetName(Property.ofValue("report-1"))
            .role(Property.ofValue(AssetRole.VIEWER))
            .members(Property.ofValue(List.of("allUsers")))
            .build();
        assertThrows(IllegalArgumentException.class, () -> blocked.run(TestsUtils.mockRunContext(this.runContextFactory, blocked, Map.of())));

        Add malformed = Add.builder()
            .id(taskId())
            .type(Add.class.getName())
            .accessToken(Property.ofValue("token-1"))
            .assetName(Property.ofValue("report-1"))
            .role(Property.ofValue(AssetRole.VIEWER))
            .members(Property.ofValue(List.of("ada@acme.com")))
            .build();
        assertThrows(IllegalArgumentException.class, () -> malformed.run(TestsUtils.mockRunContext(this.runContextFactory, malformed, Map.of())));

        Revoke revoke = Revoke.builder()
            .id(taskId())
            .type(Revoke.class.getName())
            .accessToken(Property.ofValue("token-1"))
            .assetName(Property.ofValue("report-1"))
            .members(Property.ofValue(List.of("everyone")))
            .build();
        IllegalArgumentException revokeError = assertThrows(
            IllegalArgumentException.class,
            () -> revoke.run(TestsUtils.mockRunContext(this.runContextFactory, revoke, Map.of()))
        );
        assertTrue(revokeError.getMessage().contains("domain:"));
        assertTrue(revokeError.getMessage().contains("allUsers"));
    }

    @Test
    void linkRoleIsSentOnlyWhenOptedIn() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            Add add = Add.builder()
                .id(taskId())
                .type(Add.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .allowLinkSharing(Property.ofValue(true))
                .linkRole(Property.ofValue(LinkRole.LINK_VIEWER))
                .members(Property.ofValue(List.of("allUsers")))
                .build();
            add.run(TestsUtils.mockRunContext(this.runContextFactory, add, Map.of()));
            assertTrue(api.lastBody.contains("LINK_VIEWER"));
            assertTrue(api.lastBody.contains("allUsers"));
        }
    }

    @Test
    void linkRoleIsRejectedForNamedMembersAndViewerIsRejectedForAllUsers() throws Exception {
        Add namedWithLinkRole = Add.builder()
            .id(taskId())
            .type(Add.class.getName())
            .accessToken(Property.ofValue("token-1"))
            .assetName(Property.ofValue("report-1"))
            .allowLinkSharing(Property.ofValue(true))
            .linkRole(Property.ofValue(LinkRole.LINK_VIEWER))
            .members(Property.ofValue(List.of("user:ada@acme.com")))
            .build();
        assertThrows(IllegalArgumentException.class, () -> namedWithLinkRole.run(TestsUtils.mockRunContext(this.runContextFactory, namedWithLinkRole, Map.of())));

        Add allUsersAsViewer = Add.builder()
            .id(taskId())
            .type(Add.class.getName())
            .accessToken(Property.ofValue("token-1"))
            .assetName(Property.ofValue("report-1"))
            .allowLinkSharing(Property.ofValue(true))
            .role(Property.ofValue(AssetRole.VIEWER))
            .members(Property.ofValue(List.of("allUsers")))
            .build();
        assertThrows(IllegalArgumentException.class, () -> allUsersAsViewer.run(TestsUtils.mockRunContext(this.runContextFactory, allUsersAsViewer, Map.of())));

        try (FakeApi api = FakeApi.start()) {
            Add domainAsViewer = Add.builder()
                .id(taskId())
                .type(Add.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetName(Property.ofValue("report-1"))
                .allowLinkSharing(Property.ofValue(true))
                .role(Property.ofValue(AssetRole.VIEWER))
                .members(Property.ofValue(List.of("domain:acme.com", "user:ada@acme.com")))
                .build();
            domainAsViewer.run(TestsUtils.mockRunContext(this.runContextFactory, domainAsViewer, Map.of()));
            assertTrue(api.lastBody.contains("\"role\":\"VIEWER\""));
            assertTrue(api.lastBody.contains("domain:acme.com"));
            assertFalse(api.lastBody.contains("LINK_"));
        }
    }

    @Test
    void removedAssetDoesNotFireAndANewOneDoes() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales","assetType":"REPORT","updateTime":"2026-04-01T00:00:00Z"}]}
                """;
            Trigger trigger = trigger(api);
            var baseline = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            assertTrue(trigger.evaluate(baseline.getKey(), baseline.getValue()).isEmpty());

            api.searchBody = """
                {"assets":[]}
                """;
            var removed = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            assertTrue(trigger.evaluate(removed.getKey(), removed.getValue()).isEmpty());

            api.searchBody = """
                {"assets":[{"name":"report-2","title":"New","assetType":"REPORT","updateTime":"2026-04-03T00:00:00Z"}]}
                """;
            var created = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            var execution = trigger.evaluate(created.getKey(), created.getValue());
            assertTrue(execution.isPresent());
            assertEquals("New", execution.get().getTrigger().getVariables().get("title"));
        }
    }

    @Test
    void truncatedSearchDoesNotMoveTheWatermark() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.endlessPages = true;
            Trigger trigger = trigger(api);
            var context = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            assertThrows(IOException.class, () -> trigger.evaluate(context.getKey(), context.getValue()));

            api.endlessPages = false;
            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales","assetType":"REPORT","updateTime":"2026-04-01T00:00:00Z"}]}
                """;
            var again = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            assertTrue(trigger.evaluate(again.getKey(), again.getValue()).isEmpty());
        }
    }

    @Test
    void triggerMaxPagesCanBeRaisedAndStillFailsClosed() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.endlessPages = true;
            Trigger trigger = Trigger.builder()
                .id("watch")
                .type(Trigger.class.getName())
                .accessToken(Property.ofValue("token-1"))
                .baseUrl(Property.ofValue(api.baseUrl()))
                .assetTypes(Property.ofValue(List.of(AssetType.REPORT)))
                .maxPages(Property.ofValue(1))
                .interval(Duration.ofMinutes(5))
                .build();
            var context = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            IOException error = assertThrows(IOException.class, () -> trigger.evaluate(context.getKey(), context.getValue()));
            assertTrue(error.getMessage().contains("exceeded 1 pages"));

            api.endlessPages = false;
            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales","assetType":"REPORT","updateTime":"2026-04-01T00:00:00Z"}]}
                """;
            var again = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            assertTrue(trigger.evaluate(again.getKey(), again.getValue()).isEmpty());
        }
    }

    @Test
    void watermarkIsIsolatedPerFlow() throws Exception {
        try (FakeApi api = FakeApi.start()) {
            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales","assetType":"REPORT","updateTime":"2026-04-01T00:00:00Z"}]}
                """;
            Trigger trigger = trigger(api);
            var first = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            assertTrue(trigger.evaluate(first.getKey(), first.getValue()).isEmpty());

            api.searchBody = """
                {"assets":[{"name":"report-1","title":"Sales Q2","assetType":"REPORT","updateTime":"2026-04-02T00:00:00Z"}]}
                """;
            Flow otherFlow = Flow.builder()
                .id("other-flow")
                .namespace(first.getKey().getFlow().getNamespace())
                .tenantId(first.getKey().getFlow().getTenantId())
                .revision(1)
                .build();
            ConditionContext otherCondition = ConditionContext.builder()
                .runContext(first.getKey().getRunContext())
                .flow(otherFlow)
                .build();
            TriggerContext otherContext = TriggerContext.builder()
                .namespace(otherFlow.getNamespace())
                .flowId(otherFlow.getId())
                .triggerId(trigger.getId())
                .date(ZonedDateTime.now())
                .build();
            assertTrue(trigger.evaluate(otherCondition, otherContext).isEmpty());

            var original = TestsUtils.mockTrigger(this.runContextFactory, trigger);
            var execution = trigger.evaluate(original.getKey(), original.getValue());
            assertTrue(execution.isPresent());
            assertEquals("Sales Q2", execution.get().getTrigger().getVariables().get("title"));
        }
    }

    private Search search(FakeApi api) {
        return search(api, FetchType.FETCH, null);
    }

    private Search search(FakeApi api, FetchType fetchType, Integer maxPages) {
        Search.SearchBuilder<?, ?> builder = Search.builder()
            .id(taskId())
            .type(Search.class.getName())
            .accessToken(Property.ofValue("token-1"))
            .baseUrl(Property.ofValue(api.baseUrl()))
            .assetTypes(Property.ofValue(List.of(AssetType.REPORT)))
            .fetchType(Property.ofValue(fetchType));
        if (maxPages != null) {
            builder.maxPages(Property.ofValue(maxPages));
        }
        return builder.build();
    }

    private Trigger trigger(FakeApi api) {
        return Trigger.builder()
            .id("watch")
            .type(Trigger.class.getName())
            .accessToken(Property.ofValue("token-1"))
            .baseUrl(Property.ofValue(api.baseUrl()))
            .assetTypes(Property.ofValue(List.of(AssetType.REPORT)))
            .interval(Duration.ofMinutes(5))
            .build();
    }

    private static String taskId() {
        return "task-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String serviceAccount(KeyPair pair) throws Exception {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        return JacksonMapper.ofJson().writeValueAsString(Map.of(
            "client_email", "studio@project.iam.gserviceaccount.com",
            "private_key", pem
        ));
    }

    private static String assertionOf(String body) {
        for (String part : body.split("&")) {
            if (part.startsWith("assertion=")) {
                return java.net.URLDecoder.decode(part.substring("assertion=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String jwtPayload(String assertion) {
        String[] parts = assertion.split("\\.");
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    private static final class FakeApi implements AutoCloseable {
        private final HttpServer server;
        private String searchBody = """
            {"assets":[]}
            """;
        private String trashedBody;
        private String permissionsBody = """
            {"permissions":{}}
            """;
        private String lastMethod;
        private String lastPath;
        private String lastQuery;
        private String lastBody;
        private String lastAuthorization;
        private int status = 200;
        private int tokenStatus = 200;
        private boolean endlessPages = false;
        private int searchCalls = 0;
        private List<String> searchPages;
        private final List<String> queries = new ArrayList<>();
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
                api.queries.add(query);
                int code = 200;
                byte[] response;
                if ("/token".equals(path)) {
                    api.onToken.accept(api.lastBody);
                    code = api.tokenStatus;
                    response = code >= 400
                        ? "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8)
                        : """
                        {"access_token":"ya29.test","token_type":"Bearer","expires_in":3600}
                        """.getBytes(StandardCharsets.UTF_8);
                } else if (path.endsWith("/permissions") || path.contains("permissions:")) {
                    response = api.permissionsBody.getBytes(StandardCharsets.UTF_8);
                } else if (api.endlessPages) {
                    int call = api.searchCalls++;
                    response = ("{\"assets\":[{\"name\":\"report-" + call + "\",\"title\":\"Page\",\"assetType\":\"REPORT\",\"updateTime\":\"2026-04-01T00:00:00Z\"}],\"nextPageToken\":\"page-" + (call + 1) + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                    code = api.status;
                } else if (query != null && query.contains("includeTrashed=true") && api.trashedBody != null) {
                    response = api.trashedBody.getBytes(StandardCharsets.UTF_8);
                    code = api.status;
                } else if (api.searchPages != null) {
                    int index = query != null && query.contains("pageToken=page-2") ? 1 : 0;
                    response = api.searchPages.get(index).getBytes(StandardCharsets.UTF_8);
                    code = api.status;
                } else {
                    response = api.searchBody.getBytes(StandardCharsets.UTF_8);
                    code = api.status;
                }
                if (code >= 400 && "/token".equals(path) == false && response.length > 0 && api.status >= 400) {
                    response = "{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, response.length);
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
