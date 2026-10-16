package io.kestra.plugin.datastudio;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import io.kestra.core.junit.annotations.ExecuteFlow;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@KestraTest(startRunner = true)
class RunnerTest {
    // The sanity flow is static YAML, so the fake API listens on the port written in it.
    private static final int PORT = 18089;
    private static HttpServer server;

    @BeforeAll
    static void startApi() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", exchange -> {
            var response = """
                {"assets":[{"name":"report-1","title":"Sales","assetType":"REPORT"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopApi() {
        server.stop(0);
    }

    @Test
    @ExecuteFlow("sanity-checks/datastudio_search.yaml")
    void datastudio_search(Execution execution) {
        assertThat(execution.getTaskRunList(), hasSize(2));
        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }
}
