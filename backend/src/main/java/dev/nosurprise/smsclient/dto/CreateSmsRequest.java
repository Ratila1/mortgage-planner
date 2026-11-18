package dev.nosurprise.smsclient.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateSmsRequest {

    @NotBlank
    private String phone;

    public CreateSmsRequest() {}

    public CreateSmsRequest(String phone) {
        this.phone = phone;
    }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
