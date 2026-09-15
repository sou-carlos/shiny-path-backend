package br.com.shinypath;

import br.com.shinypath.user.UserRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShinyPathBackendApplicationTests {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;

    private CookieManager cookies;
    private HttpClient client;

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void closeDatabase() throws IOException { POSTGRES.close(); }

    @BeforeEach
    void setup() {
        users.deleteAll();
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    @Test
    void registerPersistsHashedPasswordAndRestoresSession() throws Exception {
        assertThat(get("/me").statusCode()).isEqualTo(401);
        var token = csrf();
        var oldSession = sessionId();
        var response = post("/register", registration("  EXPLORER@example.com  "), token);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).doesNotContain("password", "passwordHash", "segredo123");
        assertThat(json(response).get("email").asText()).isEqualTo("explorer@example.com");
        assertThat(json(response).get("name").asText()).isEqualTo("Scot Explorador");
        assertThat(sessionId()).isNotEqualTo(oldSession);
        assertThat(response.headers().allValues("Set-Cookie").toString().toLowerCase()).contains("httponly", "samesite=lax");
        var persisted = users.findByEmail("explorer@example.com").orElseThrow();
        assertThat(persisted.getPasswordHash()).isNotEqualTo("segredo123");
        assertThat(passwords.matches("segredo123", persisted.getPasswordHash())).isTrue();
        assertThat(get("/me").statusCode()).isEqualTo(200);
        assertThat(json(get("/me")).get("id").asText()).isEqualTo(persisted.getId().toString());
    }

    @Test
    void loginAndLogoutRotateCsrfAndInvalidateSession() throws Exception {
        assertThat(post("/register", registration("scot@example.com"), csrf()).statusCode()).isEqualTo(201);
        assertThat(post("/logout", Map.of(), csrf()).statusCode()).isEqualTo(204);
        assertThat(get("/me").statusCode()).isEqualTo(401);
        var beforeLogin = csrf();
        var login = post("/login", Map.of("email", " SCOT@example.com ", "password", "segredo123"), beforeLogin);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(get("/me").statusCode()).isEqualTo(200);
        assertThat(post("/logout", Map.of(), beforeLogin).statusCode()).isEqualTo(403);
        assertThat(post("/logout", Map.of(), csrf()).statusCode()).isEqualTo(204);
        assertThat(get("/me").statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsDuplicateNormalizedEmail() throws Exception {
        assertThat(post("/register", registration("scot@example.com"), csrf()).statusCode()).isEqualTo(201);
        var duplicate = post("/register", registration(" SCOT@EXAMPLE.COM "), csrf());
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void validatesRegistrationAndRejectsMalformedJson() throws Exception {
        var response = post("/register", Map.of("name", " ", "email", "invalid", "password", "123"), csrf());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("fieldErrors").has("name")).isTrue();
        assertThat(json(response).get("fieldErrors").has("email")).isTrue();
        assertThat(json(response).get("fieldErrors").has("password")).isTrue();
        assertThat(users.count()).isZero();
        var token = csrf();
        var malformed = client.send(HttpRequest.newBuilder(uri("/register"))
            .header("Content-Type", "application/json")
            .header(token.get("headerName").asText(), token.get("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("{broken")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(malformed.statusCode()).isEqualTo(400);
    }

    @Test
    void wrongPasswordAndUnknownAccountReturnSameError() throws Exception {
        assertThat(post("/register", registration("scot@example.com"), csrf()).statusCode()).isEqualTo(201);
        post("/logout", Map.of(), csrf());
        var wrong = post("/login", Map.of("email", "scot@example.com", "password", "senhaErrada"), csrf());
        var unknown = post("/login", Map.of("email", "missing@example.com", "password", "senhaErrada"), csrf());
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).isEqualTo(unknown.body());
        assertThat(get("/me").statusCode()).isEqualTo(401);
    }

    @Test
    void requiresCsrfForLoginRegistrationAndLogout() throws Exception {
        for (String endpoint : new String[]{"/login", "/register", "/logout"}) {
            assertThat(post(endpoint, registration("scot@example.com"), null).statusCode()).isEqualTo(403);
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void allowsOnlyConfiguredCorsOrigins() throws Exception {
        var allowed = client.send(HttpRequest.newBuilder(uri("/login"))
            .header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
        var denied = client.send(HttpRequest.newBuilder(uri("/login"))
            .header("Origin", "https://untrusted.example")
            .header("Access-Control-Request-Method", "POST")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
    }


    @Test
    void progressPersistsAcrossSessionsAndIsIsolatedWithConflictProtection() throws Exception {
        assertThat(progressGet().statusCode()).isEqualTo(401);
        var accountId = json(post("/register", registration("progress@example.com"), csrf())).get("id").asText();
        assertThat(json(progressGet()).get("document").isNull()).isTrue();
        var fixture = JSON.readTree(getClass().getResourceAsStream("/progress-fixture.json"));
        var write = Map.of("userId", accountId, "revision", 0, "writeId", java.util.UUID.randomUUID().toString(), "document", fixture);
        assertThat(progressPost(write, null).statusCode()).isEqualTo(403);
        var saved = progressPost(write, csrf());
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(json(saved).get("revision").asInt()).isEqualTo(1);
        assertThat(json(saved).get("document")).isEqualTo(fixture);
        assertThat(json(progressPost(write, csrf())).get("revision").asInt()).isEqualTo(1);
        assertThat(progressPost(Map.of("userId", accountId, "revision", 0, "writeId", java.util.UUID.randomUUID().toString(), "document", fixture), csrf()).statusCode()).isEqualTo(409);
        assertThat(progressPost(Map.of("userId", accountId, "revision", 1, "writeId", java.util.UUID.randomUUID().toString(), "document", Map.of()), csrf()).statusCode()).isEqualTo(400);
        post("/logout", Map.of(), csrf());
        post("/register", registration("other@example.com"), csrf());
        assertThat(json(progressGet()).get("document").isNull()).isTrue();
        assertThat(progressPost(write, csrf()).statusCode()).isEqualTo(409);
        assertThat(json(progressGet()).get("document").isNull()).isTrue();
        post("/logout", Map.of(), csrf());
        post("/login", registration("progress@example.com"), csrf());
        assertThat(json(progressGet()).get("document")).isEqualTo(fixture);
        assertThat(json(progressGet()).get("revision").asInt()).isEqualTo(1);
    }

    private HttpResponse<String> progressGet() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/progress")).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> progressPost(Object body, JsonNode token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/progress")).header("Content-Type", "application/json");
        if (token != null) request.header(token.get("headerName").asText(), token.get("token").asText());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> registration(String email) {
        return Map.of("name", "  Scot Explorador  ", "email", email, "password", "segredo123");
    }

    private URI uri(String endpoint) { return URI.create("http://localhost:" + port + "/api/auth" + endpoint); }

    private HttpResponse<String> get(String endpoint) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(endpoint)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode csrf() throws Exception { return json(get("/csrf")); }

    private String sessionId() {
        return cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("SHINY_PATH_SESSION")).findFirst().orElseThrow().getValue();
    }

    private HttpResponse<String> post(String endpoint, Object body, JsonNode token) throws Exception {
        var request = HttpRequest.newBuilder(uri(endpoint)).header("Content-Type", "application/json");
        if (token != null) request.header(token.get("headerName").asText(), token.get("token").asText());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) { return JSON.readTree(response.body()); }
}
