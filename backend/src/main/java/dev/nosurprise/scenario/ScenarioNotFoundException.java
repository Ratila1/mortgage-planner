package dev.nosurprise.scenario;

public class ScenarioNotFoundException extends RuntimeException {
    public ScenarioNotFoundException(String message) {
        super(message);
    }
}