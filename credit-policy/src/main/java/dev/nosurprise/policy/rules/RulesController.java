package dev.nosurprise.policy.rules;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Tag(name = "Правила скоринга")
@RestController
@RequestMapping("/api/admin/rules")
public class RulesController {

    private final RuleService rules;

    public RulesController(RuleService rules) {
        this.rules = rules;
    }

    public record RuleSetResponse(int version, boolean active, String comment, UUID createdBy, Instant createdAt,
                                  RuleSet rules) {
    }

    public record CreateRuleSetRequest(@NotNull @Valid RuleSet rules, @Size(max = 500) String comment) {
    }

    @Operation(summary = "Активная версия правил")
    @GetMapping
    public RuleSetResponse active() {
        ActiveRules active = rules.active();
        return response(rules.get(active.version()));
    }

    @Operation(summary = "Все версии правил, новые сверху")
    @GetMapping("/versions")
    public List<RuleSetResponse> versions() {
        return rules.versions().stream().map(this::response).toList();
    }

    @Operation(summary = "Версия правил по номеру")
    @GetMapping("/{version}")
    public RuleSetResponse get(@PathVariable int version) {
        return response(rules.get(version));
    }

    @Operation(summary = "Новая версия правил (сохраняется неактивной)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleSetResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRuleSetRequest request) {
        return response(rules.create(request.rules(), request.comment(), UUID.fromString(jwt.getSubject())));
    }

    @Operation(summary = "Сделать версию активной: новые расчёты пойдут по ней")
    @PostMapping("/{version}/activate")
    public RuleSetResponse activate(@PathVariable int version) {
        return response(rules.activate(version));
    }

    private RuleSetResponse response(RuleSetEntity entity) {
        return new RuleSetResponse(entity.getVersion(), entity.isActive(), entity.getComment(), entity.getCreatedBy(),
                entity.getCreatedAt(), rules.parse(entity));
    }
}
