package dev.nosurprise.sms.telegram.dto;

public record VerificationStatusResponse(String verificationId, String status, boolean codeSent) {}
