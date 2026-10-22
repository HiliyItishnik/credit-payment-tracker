package dev.portfolio.credittracker.api;

import dev.portfolio.credittracker.domain.AmortizationCalculator.Plan;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LoanDtos {
    private LoanDtos() { }

    public record CreateLoan(
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin("0.01") @DecimalMax("100000000") @Digits(integer = 9, fraction = 2) BigDecimal principal,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal annualRate,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000000") @Digits(integer = 10, fraction = 2) BigDecimal monthlyPayment,
            @NotNull LocalDate firstPaymentDate) {
        public CreateLoan { if (name != null) name = name.strip(); }
    }

    public record RecordPayment(
            @NotNull UUID requestId,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000000") @Digits(integer = 10, fraction = 2) BigDecimal amount) { }
    public record LoanSummary(UUID id, String name, BigDecimal principal, BigDecimal balance, BigDecimal annualRate) { }
    public record PaymentView(UUID id, int number, LocalDate date, BigDecimal amount, BigDecimal interest,
                              BigDecimal principal, BigDecimal balance, Instant recordedAt) { }
    public record LoanDetail(UUID id, String name, BigDecimal principal, BigDecimal annualRate,
                             BigDecimal monthlyPayment, LocalDate firstPaymentDate, BigDecimal balance,
                             BigDecimal principalPaid, BigDecimal interestPaid, BigDecimal totalPaid,
                             boolean paidOff, LocalDate nextPaymentDate, BigDecimal nextInterest,
                             BigDecimal suggestedPayment, BigDecimal maximumPayment, List<PaymentView> payments) { }
    public record Forecast(Plan baseline, Plan scenario, BigDecimal interestSaved, int monthsSaved) { }
}
