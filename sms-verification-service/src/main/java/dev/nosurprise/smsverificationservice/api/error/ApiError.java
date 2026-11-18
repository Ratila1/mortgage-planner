package dev.nosurprise.smsverificationservice.api.error;

import java.time.Instant;

public class ApiError {

    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String path;

    public ApiError() {}

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Instant timestamp;
        private int status;
        private String error;
        private String message;
        private String path;

        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder status(int status) { this.status = status; return this; }
        public Builder error(String error) { this.error = error; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder path(String path) { this.path = path; return this; }

        public ApiError build() {
            ApiError error = new ApiError();
            error.timestamp = this.timestamp;
            error.status = this.status;
            error.error = this.error;
            error.message = this.message;
            error.path = this.path;
            return error;
        }
    }
}