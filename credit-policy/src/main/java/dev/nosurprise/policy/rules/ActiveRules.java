package dev.nosurprise.policy.rules;

/** Активная версия правил, разобранная из JSON. */
public record ActiveRules(int version, RuleSet rules) {
}
