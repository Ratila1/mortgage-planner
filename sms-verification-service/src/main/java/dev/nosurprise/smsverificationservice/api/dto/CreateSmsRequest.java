package dev.nosurprise.smsverificationservice.api.dto;

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

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String phone;

        public Builder phone(String phone) { this.phone = phone; return this; }
        public CreateSmsRequest build() {
            CreateSmsRequest dto = new CreateSmsRequest();
            dto.phone = this.phone;
            return dto;
        }
    }
}