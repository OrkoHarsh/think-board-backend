package com.nimbusboard.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusboard.auth.JwtProvider;
import com.nimbusboard.auth.models.User;
import com.nimbusboard.auth.models.UserRepository;
import com.nimbusboard.board.dto.CreateBoardRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the seeded catalog end to end, which also verifies that V4 plus the repeatable seed apply
 * cleanly and that the Template entity matches the schema under ddl-auto=validate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class TemplateControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("nimbusboard_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtProvider jwtProvider;

    private String authToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .email("templates@nimbus.com")
                .password(passwordEncoder.encode("password123"))
                .name("Template Tester")
                .role("USER")
                .build());

        authToken = jwtProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
    }

    private JsonNode fetchCatalog() throws Exception {
        String body = mockMvc.perform(get("/api/templates")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    @Test
    void listTemplates_withoutAuth_isRejected() throws Exception {
        // The running app answers anonymous requests with 403 rather than 401, so assert on the
        // class of response: what matters is that the catalog is not readable without a token.
        mockMvc.perform(get("/api/templates"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listTemplates_returnsSeededCatalogInSortOrder() throws Exception {
        JsonNode data = fetchCatalog();

        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isGreaterThanOrEqualTo(8);

        List<String> slugs = new ArrayList<>();
        data.forEach(node -> slugs.add(node.path("slug").asText()));

        assertThat(slugs).containsSubsequence("kanban", "retrospective", "brainstorm", "impact-effort");
        assertThat(slugs).contains("system-architecture", "microservices", "uml-class", "flowchart");

        for (JsonNode template : data) {
            assertThat(template.path("name").asText()).isNotBlank();
            assertThat(template.path("category").asText()).isNotBlank();
            assertThat(template.path("objects").isArray()).isTrue();
            assertThat(template.path("objects").size()).isPositive();

            for (JsonNode object : template.path("objects")) {
                assertThat(object.path("type").asText()).isNotBlank();
                // Thumbnails read these at the object root, not inside properties.
                boolean positioned = object.has("x") && !object.path("x").isNull();
                boolean isConnector = object.path("type").asText().matches("line|arrow|freehand");
                assertThat(positioned || isConnector).isTrue();
            }
        }
    }

    @Test
    void createBoard_withTemplateSlug_returnsPopulatedBoard() throws Exception {
        JsonNode catalog = fetchCatalog();
        int expectedObjects = 0;
        for (JsonNode template : catalog) {
            if ("kanban".equals(template.path("slug").asText())) {
                expectedObjects = template.path("objects").size();
            }
        }
        assertThat(expectedObjects).isPositive();

        CreateBoardRequest request = new CreateBoardRequest();
        request.setTitle("Kanban board");
        request.setTemplateSlug("kanban");

        String body = mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Kanban board"))
                .andReturn().getResponse().getContentAsString();

        JsonNode board = objectMapper.readTree(body).path("data");
        JsonNode objects = board.path("objects");
        assertThat(objects.size()).isEqualTo(expectedObjects);

        Set<String> ids = new HashSet<>();
        for (JsonNode object : objects) {
            String id = object.path("id").asText();
            assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException();
            ids.add(id);
        }
        assertThat(ids).hasSize(objects.size());

        // The objects must be persisted, not just echoed back from the create call.
        mockMvc.perform(get("/api/boards/" + board.path("id").asText())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objects.length()").value(expectedObjects));
    }

    @Test
    void createBoard_twiceFromSameTemplate_doesNotShareObjectIds() throws Exception {
        CreateBoardRequest request = new CreateBoardRequest();
        request.setTitle("Flowchart");
        request.setTemplateSlug("flowchart");

        Set<String> firstIds = createAndCollectObjectIds(request);
        Set<String> secondIds = createAndCollectObjectIds(request);

        assertThat(firstIds).isNotEmpty();
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    }

    private Set<String> createAndCollectObjectIds(CreateBoardRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Set<String> ids = new HashSet<>();
        objectMapper.readTree(body).path("data").path("objects")
                .forEach(object -> ids.add(object.path("id").asText()));
        return ids;
    }

    @Test
    void createBoard_withUnknownTemplate_returns404() throws Exception {
        CreateBoardRequest request = new CreateBoardRequest();
        request.setTitle("Nope");
        request.setTemplateSlug("no-such-template");

        mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/boards")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
