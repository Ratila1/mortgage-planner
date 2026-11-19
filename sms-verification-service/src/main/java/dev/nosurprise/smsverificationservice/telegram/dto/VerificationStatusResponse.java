package dev.nosurprise.smsverificationservice.telegram.dto;

public record VerificationStatusResponse(String verificationId, String status, boolean codeSent) {}
