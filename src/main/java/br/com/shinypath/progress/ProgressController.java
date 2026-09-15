package br.com.shinypath.progress;

import br.com.shinypath.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/progress")
public class ProgressController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final JsonMapper json;

    public ProgressController(JdbcTemplate jdbc, UserRepository users, JsonMapper json) {
        this.jdbc = jdbc;
        this.users = users;
        this.json = json;
    }

    public record Island(@NotBlank @Size(max=60) String id,
        @Pattern(regexp="completed|uncompleted|locked") @NotNull String status,
        @Pattern(regexp="clean-code|variables|functions|comments|formatting") @NotNull String section) {}
    public record Game(@Min(0) @Max(5) int lives, @Min(0) @Max(1000000) int points,
        @Min(0) @Max(1000000) int streak, @Min(0) @Max(1000000) int maxStreak,
        @Min(0) int totalCorrect, @Min(0) int totalAttempts,
        @NotNull @Size(max=50) List<@NotBlank @Size(max=100) String> achievements,
        @NotNull @Size(max=10) List<@NotBlank @Size(max=100) String> medals) {}
    public record Document(boolean welcomeCompleted,
        @NotNull @Size(max=5) List<@NotNull @Pattern(regexp="clean-code|variables|functions|comments|formatting") String> introducedTrails,
        @NotNull @Size(min=34, max=34) List<@NotNull @Valid Island> islandProgress,
        @NotNull @Valid Game gamificationState,
        @NotNull @Size(max=34) List<@NotBlank @Size(max=60) String> rewardedIslands) {}
    public record Write(@NotNull UUID userId, @Min(0) long revision, @NotNull UUID writeId, @NotNull @Valid Document document) {}
    public record Snapshot(UUID userId, long revision, Document document, UUID lastWriteId) {}

    private UUID userId(Authentication auth) {
        return users.findByEmail(auth.getName()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Conta não encontrada.")).getId();
    }

    private Snapshot read(UUID userId) {
        var rows = jdbc.query("SELECT revision, document::text, last_write_id FROM user_progress WHERE user_id = ?",
            (rs, row) -> new Snapshot(userId, rs.getLong(1), rs.getString(2) == null ? null : json.readValue(rs.getString(2), Document.class),
                rs.getObject(3, UUID.class)), userId);
        return rows.isEmpty() ? new Snapshot(userId, 0, null, null) : rows.getFirst();
    }

    @GetMapping
    public Snapshot get(Authentication auth) { return read(userId(auth)); }

    @PostMapping
    @Transactional
    public Snapshot save(Authentication auth, @Valid @RequestBody Write request) {
        var document = request.document();
        Set<String> ids = new HashSet<>();
        for (var island : document.islandProgress()) {
            if (!ids.add(island.id()) || !validIsland(island)) invalid();
        }
        if (!ids.containsAll(document.rewardedIslands())
            || document.gamificationState().totalCorrect() > document.gamificationState().totalAttempts()
            || document.gamificationState().streak() > document.gamificationState().maxStreak()) invalid();

        var id = userId(auth);
        if (!id.equals(request.userId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A conta conectada mudou. Recarregue para continuar.");
        }
        jdbc.update("INSERT INTO user_progress(user_id) VALUES (?) ON CONFLICT DO NOTHING", id);
        // Lock only this account; the revision prevents an older tab from overwriting newer progress.
        jdbc.queryForObject("SELECT revision FROM user_progress WHERE user_id = ? FOR UPDATE", Long.class, id);
        var current = read(id);
        if (request.writeId().equals(current.lastWriteId())) {
            if (!request.document().equals(current.document())) invalid();
            return current;
        }
        if (request.revision() != current.revision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Seu progresso foi atualizado em outro acesso. Recarregue para continuar.");
        }
        jdbc.update("UPDATE user_progress SET document = CAST(? AS jsonb), revision = revision + 1, last_write_id = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
            json.writeValueAsString(document), request.writeId(), id);
        return read(id);
    }

    private static boolean validIsland(Island island) {
        return switch (island.section()) {
            case "clean-code" -> island.id().matches("codigo-limpo-([1-4]|comparacao)");
            case "variables" -> island.id().matches("ilha-[1-7]|variaveis-comparacao");
            case "functions" -> island.id().matches("funcao-[1-6]|funcoes-comparacao");
            case "comments" -> island.id().matches("comentario-[1-6]|comentarios-comparacao");
            case "formatting" -> island.id().matches("formatacao-[1-6]|formatacao-comparacao");
            default -> false;
        };
    }
    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Progresso inválido.");
    }
}
