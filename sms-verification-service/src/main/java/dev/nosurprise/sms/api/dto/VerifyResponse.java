package dev.nosurprise.sms.api.dto;

public record VerifyResponse(boolean verified, String reason) {}
