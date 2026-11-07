package com.agentcore.spring.web;

import static org.assertj.core.api.Assertions.*;

import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.interfaces.web.ModelCatalog;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileModelCatalogTest {
    @TempDir Path directory;
    ModelGateway startup = new ScriptedModelGateway().otherwise(r -> ScriptedModelGateway.reply("startup"));

    ObjectNode profile() {
        return Json.object().put("label", "Local model").put("type", "openai").put("model", "example")
                .put("base_url", "http://localhost:1234/v1").put("api_key", "secret-value");
    }

    @Test void keysAreRedactedPreservedAndNeverForwardedToChangedOrigins() {
        FileModelCatalog catalog = new FileModelCatalog(startup, null);
        catalog.put("local", profile());
        assertThat(catalog.models(true).toString()).doesNotContain("secret-value", "localhost", "base_url", "api_key");
        assertThat(catalog.settings().toString()).doesNotContain("secret-value", "\"api_key\"");
        assertThat(catalog.settings().path("profiles").get(0).path("has_api_key").asBoolean()).isTrue();
        ObjectNode changed = profile(); changed.remove("api_key");
        catalog.put("local", changed);
        catalog.put("local", changed.put("api_key", ""));
        assertThat(catalog.settings().path("profiles").get(0).path("has_api_key").asBoolean()).isTrue();
        catalog.put("local", changed.put("base_url", "http://LOCALHOST:1234/v2"));
        assertThatThrownBy(() -> catalog.put("local", changed.put("base_url", "http://127.0.0.1:1234/v1")))
                .isInstanceOf(ModelCatalog.Failure.class).hasMessageContaining("clear_api_key").hasMessageNotContaining("secret-value");
        assertThat(catalog.settings().path("profiles").get(0).path("base_url").asText()).contains("LOCALHOST");
        catalog.put("local", changed.put("clear_api_key", true));
        assertThat(catalog.settings().path("profiles").get(0).path("has_api_key").asBoolean()).isFalse();
        changed.remove("clear_api_key");
        catalog.put("local", changed.put("api_key", "fresh-key").put("base_url", "https://example.invalid/v1"));
        assertThat(catalog.settings().path("profiles").get(0).path("has_api_key").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x", "default", "mock", "server-default", "a/b", "a.b", "UPPER", "9start", ""})
    void rejectsUnsafeAndReservedIds(String id) {
        assertThatThrownBy(() -> new FileModelCatalog(startup, null).put(id, profile())).isInstanceOf(ModelCatalog.Failure.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/a", "ftp://localhost/v1", "http://key@localhost/v1", "http://localhost/?key=secret",
            "http://localhost/#key", "http://localhost:0/v1", "http://localhost:65536/v1", "//localhost/v1", "http:localhost"})
    void rejectsUnsafeUrlsWithoutEchoingValues(String url) {
        assertThatThrownBy(() -> new FileModelCatalog(startup, null).put("local", profile().put("base_url", url)))
                .isInstanceOf(ModelCatalog.Failure.class).hasMessageNotContaining(url);
    }

    @Test void validatesNumbersLengthsFieldsAndLimit() {
        FileModelCatalog catalog = new FileModelCatalog(startup, null);
        for (ObjectNode bad : List.of(profile().put("max_tokens", 0), profile().put("max_tokens", 1.5),
                profile().put("context_window", Long.MAX_VALUE), profile().put("label", "a".repeat(121)),
                profile().put("type", "hidden-provider"), profile().put("api_key", "a\nb"),
                profile().put("clear_api_key", "true"), profile().put("max_tokens", 200_000),
                profile().put("api_key_env", "HIDDEN_KEY"), profile().put("base_url", "a".repeat(2049)),
                profile().put("model", "m".repeat(257)))) {
            assertThatThrownBy(() -> catalog.put("local", bad)).isInstanceOf(ModelCatalog.Failure.class);
        }
        for (int i = 0; i < 64; i++) catalog.put("p" + i, profile());
        assertThatThrownBy(() -> catalog.put("overflow", profile())).hasMessageContaining("limit");
        catalog.put("p0", profile().put("label", "updated"));
        assertThat(catalog.settings().path("profiles")).hasSize(64);
    }

    @Test void persistsRestartsWithPrivatePermissionsAndDoesNotModifyYaml() throws Exception {
        Path file = directory.resolve("private/models.json");
        Path yaml = directory.resolve("config.yaml"); Files.writeString(yaml, "unchanged: true");
        FileModelCatalog catalog = new FileModelCatalog(startup, file);
        catalog.put("local", profile()); catalog.setDefault("local");
        assertThat(Files.readString(file)).contains("secret-value");
        assertThat(Files.readString(yaml)).isEqualTo("unchanged: true");
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.getPosixFilePermissions(file.getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
        FileModelCatalog restarted = new FileModelCatalog(startup, file);
        assertThat(restarted.settings()).isEqualTo(catalog.settings());
        restarted.delete("local");
        assertThat(new FileModelCatalog(startup, file).settings().path("default_id").asText()).isEqualTo("server-default");
        try (var files = Files.list(file.getParent())) { assertThat(files.toList()).containsExactly(file); }
    }

    @Test void failedPersistenceDoesNotPublishOrDestroyPreviousState() throws Exception {
        Path file = directory.resolve("models.json");
        FileModelCatalog catalog = new FileModelCatalog(startup, file);
        catalog.put("local", profile());
        ObjectNode before = catalog.settings();
        // Replace the known exact target with a directory: rename must fail even when tests run as root.
        Files.delete(file); Files.createDirectory(file);
        assertThatThrownBy(() -> catalog.setDefault("mock")).hasMessage("could not persist model settings");
        assertThatThrownBy(() -> catalog.put("other", profile())).hasMessage("could not persist model settings");
        assertThatThrownBy(() -> catalog.delete("local")).hasMessage("could not persist model settings");
        assertThat(catalog.settings()).isEqualTo(before);
        try (var files = Files.list(directory)) { assertThat(files.toList()).containsExactly(file); }
    }

    @Test void sharedParentPermissionsAndHardLinkedFilesAreNeverModified() throws Exception {
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
        Path shared = Files.createDirectory(directory.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"));
        FileModelCatalog catalog = new FileModelCatalog(startup, shared.resolve("models.json"));
        assertThatThrownBy(() -> catalog.put("local", profile())).hasMessage("could not persist model settings");
        assertThat(catalog.settings().path("profiles")).isEmpty();
        assertThat(Files.getPosixFilePermissions(shared)).isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        Path file = directory.resolve("private.json");
        FileModelCatalog privateCatalog = new FileModelCatalog(startup, file);
        privateCatalog.put("local", profile());
        Path link = directory.resolve("hard-link.json"); Files.createLink(link, file);
        String original = Files.readString(file);
        assertThatThrownBy(() -> privateCatalog.delete("local")).hasMessage("could not persist model settings");
        assertThatThrownBy(() -> new FileModelCatalog(startup, link))
                .hasMessageStartingWith("could not load model settings from " + link)
                .hasMessageContaining("more than one hard link");
        assertThat(Files.readString(link)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai", "openai-responses"})
    void allProtocolGatewaysCanBeCreatedOfflineWithoutEnvironmentKeys(String type) {
        FileModelCatalog catalog = new FileModelCatalog(startup, null);
        ObjectNode configuration = profile().put("type", type); configuration.remove("api_key");
        catalog.put("local", configuration);
        try (var lease = catalog.select("local")) {
            assertThat(lease.gateway().model()).isEqualTo("example");
        }
    }

    @Test void refusesMalformedAndSymlinkedPersistenceWithoutLeakingSecrets() throws Exception {
        Path file = directory.resolve("models.json");
        Files.writeString(file, "{secret-value broken");
        // 错误信息要说清楚是哪个文件、什么问题，但不能带出文件内容（里面可能有 key）
        assertThatThrownBy(() -> new FileModelCatalog(startup, file))
                .hasMessageStartingWith("could not load model settings from " + file)
                .hasMessageContaining("not valid JSON")
                .hasMessageNotContaining("secret-value")
                .hasNoCause();
        Path link = directory.resolve("linked.json"); Files.createSymbolicLink(link, file);
        assertThatThrownBy(() -> new FileModelCatalog(startup, link))
                .hasMessageStartingWith("could not load model settings from " + link)
                .hasMessageContaining("symbolic link");
    }

    @Test void gatewayLeasesBorrowStartupAndKeepSnapshotsAcrossReplacementAndDeletion() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        ModelGateway borrowed = new ModelGateway() {
            public String provider() { return "test"; }
            public String model() { return "startup"; }
            public int contextWindow() { return 1000; }
            public com.agentcore.domain.llm.model.ModelResponse stream(ModelRequest r,
                    com.agentcore.domain.llm.gateway.ModelStreamListener l, com.agentcore.domain.shared.cancel.CancellationToken c) {
                return ScriptedModelGateway.reply("startup");
            }
            public void close() { closed.incrementAndGet(); }
        };
        FileModelCatalog catalog = new FileModelCatalog(borrowed, null);
        try (var selected = catalog.select(null)) { assertThat(selected.gateway()).isSameAs(borrowed); }
        assertThat(closed.get()).isZero();
        try (var first = catalog.select("mock"); var second = catalog.select("mock")) {
            assertThat(first.gateway()).isNotSameAs(second.gateway());
            assertThat(first.gateway().complete(ModelRequest.of("", List.of(), List.of())).message().text()).contains("mock");
        }
        catalog.put("custom", profile().put("model", "old"));
        try (var old = catalog.select("custom")) {
            catalog.put("custom", profile().put("model", "new"));
            try (var newer = catalog.select("custom")) { assertThat(newer.gateway().model()).isEqualTo("new"); }
            catalog.delete("custom");
            assertThat(old.gateway().model()).isEqualTo("old");
        }
        assertThatThrownBy(() -> catalog.select("unknown")).hasMessage("model profile not found");
    }

    /** Separate JVM lets the test actually supply hostile inherited provider environment variables. */
    public static class IsolatedGatewayProbe {
        public static void main(String[] args) {
            FileModelCatalog catalog = new FileModelCatalog(new ScriptedModelGateway(), null);
            catalog.put("isolated", Json.object().put("type", args[0]).put("model", "test-model")
                    .put("base_url", args[1]).put("api_key", "explicit-key"));
            try (var selected = catalog.select("isolated")) {
                try { selected.gateway().complete(ModelRequest.of("", List.of(
                        com.agentcore.domain.conversation.message.Message.user("hello")), List.of())); }
                catch (com.agentcore.domain.llm.exception.ModelException expected) { /* fake endpoint returns 400 */ }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai", "openai-responses"})
    void isolatedProfilesIgnoreRealInheritedEnvironmentCredentials(String type) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> headers = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); headers.set(exchange.getRequestHeaders().entrySet().toString());
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fake rejection\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), IsolatedGatewayProbe.class.getName(), type, endpoint);
            builder.environment().put("ANTHROPIC_AUTH_TOKEN", "hidden-auth-token");
            builder.environment().put("ANTHROPIC_API_KEY", "hidden-api-key");
            builder.environment().put("OPENAI_API_KEY", "hidden-openai-key");
            builder.environment().put("ANTHROPIC_BASE_URL", "http://127.0.0.1:1");
            builder.environment().put("ANTHROPIC_CUSTOM_HEADERS", "X-Hidden: hidden-header");
            Process process = builder.redirectErrorStream(true).start();
            boolean ended = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!ended) process.destroyForcibly();
            assertThat(ended).isTrue();
            assertThat(process.exitValue()).as(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isZero();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(headers.get()).contains("explicit-key").doesNotContain("hidden-auth-token", "hidden-api-key", "hidden-openai-key", "hidden-header");
        } finally { server.stop(0); }
    }

    @Test void localFakeGatewayUsesFrozenKeysAndModelAndRedactsUpstreamErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> model = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            model.set(Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).path("model").asText());
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"fake-ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.createContext("/error/chat/completions", exchange -> {
            byte[] response = "secret-value Authorization: Bearer secret-value".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            FileModelCatalog catalog = new FileModelCatalog(startup, null);
            catalog.put("local", profile().put("base_url", origin + "/v1"));
            assertThat(calls.get()).isZero(); // Saving is strictly offline.
            try (var selected = catalog.select("local")) {
                catalog.put("local", profile().put("base_url", origin + "/v1").put("model", "changed").put("api_key", "new-key"));
                assertThat(selected.gateway().complete(ModelRequest.of("", List.of(), List.of())).message().text()).isEqualTo("fake-ok");
                assertThat(authorization.get()).isEqualTo("Bearer secret-value");
                assertThat(model.get()).isEqualTo("example");
            }
            catalog.put("local", profile().put("base_url", origin + "/v1").put("api_key", "").put("clear_api_key", true));
            try (var selected = catalog.select("local")) {
                selected.gateway().complete(ModelRequest.of("", List.of(), List.of()));
                assertThat(authorization.get()).isNull();
            }
            catalog.put("local", profile().put("base_url", origin + "/error"));
            try (var selected = catalog.select("local")) {
                assertThatThrownBy(() -> selected.gateway().complete(ModelRequest.of("", List.of(), List.of())))
                        .hasMessage("model request failed").hasNoCause();
            }
        } finally { server.stop(0); }
    }

    @Test void loadErrorsExplainPermissionProblemsAndInvalidProfiles() throws Exception {
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
        Path folder = Files.createDirectory(directory.resolve("settings"));
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        Path file = folder.resolve("models.json");
        Files.writeString(file, "{\"default_id\":\"server-default\",\"profiles\":[{\"id\":\"local\",\"type\":\"nope\",\"api_key\":\"secret-value\"}]}");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> new FileModelCatalog(startup, file)).hasMessageContaining("chmod 600");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> new FileModelCatalog(startup, file))
                .hasMessageContaining("profile 'local' is invalid: invalid model type")
                .hasMessageNotContaining("secret-value");
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> new FileModelCatalog(startup, file)).hasMessageContaining("chmod 700");
    }
}
