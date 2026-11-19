package dev.nosurprise.notificationservice.dto;

public record VerificationStatusResponse(String verificationId, String status, boolean codeSent) {}