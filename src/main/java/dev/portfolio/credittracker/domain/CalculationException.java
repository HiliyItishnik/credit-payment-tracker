package dev.portfolio.credittracker.domain;

public class CalculationException extends RuntimeException {
    public CalculationException(String message) {
        super(message);
    }
}
