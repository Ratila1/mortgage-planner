package dev.nosurprise.policy.rules;

import dev.nosurprise.policy.common.ApiException;
import dev.nosurprise.policy.common.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Версии правил (CREDIT_POLICY.md §7.1, §9). Новая версия сохраняется неактивной; включение — отдельной командой.
 * Содержимое сохранённой версии не меняется: результат расчёта всегда воспроизводим по rules_version.
 */
@Service
public class RuleService {

    private final RuleSetRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RuleService(RuleSetRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ActiveRules active() {
        RuleSetEntity entity = repository.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException("no active rule set"));
        return new ActiveRules(entity.getVersion(), parse(entity));
    }

    @Transactional(readOnly = true)
    public List<RuleSetEntity> versions() {
        return repository.findAllByOrderByVersionDesc();
    }

    @Transactional(readOnly = true)
    public RuleSetEntity get(int version) {
        return repository.findById(version).orElseThrow(RuleService::versionNotFound);
    }

    public RuleSet parse(RuleSetEntity entity) {
        return objectMapper.readValue(entity.getRules(), RuleSet.class);
    }

    @Transactional
    public RuleSetEntity create(RuleSet rules, String comment, UUID adminId) {
        List<String> errors = rules.validate();
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.RULES_INVALID, String.join("; ", errors));
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(rules);
        } catch (JacksonException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.RULES_INVALID, "rules cannot be serialized");
        }
        int version = repository.maxVersion() + 1;
        return repository.save(new RuleSetEntity(version, json, blankToNull(comment), adminId, clock.instant()));
    }

    /** Ровно одна активная версия: сначала снимаем флаг со всех, потом ставим на выбранную (частичный индекс). */
    @Transactional
    public RuleSetEntity activate(int version) {
        RuleSetEntity target = repository.findById(version).orElseThrow(RuleService::versionNotFound);
        if (target.isActive()) {
            return target;
        }
        repository.deactivateAll();
        RuleSetEntity reloaded = repository.findById(version).orElseThrow(RuleService::versionNotFound);
        reloaded.setActive(true);
        return repository.save(reloaded);
    }

    private static ApiException versionNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.RULES_VERSION_NOT_FOUND, "rules version not found");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
