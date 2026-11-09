package dev.portfolio.credittracker.api;

import dev.portfolio.credittracker.domain.AmortizationCalculator.Plan;
import dev.portfolio.credittracker.loan.DebtType;
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
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal annualRate,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000000") @Digits(integer = 10, fraction = 2) BigDecimal monthlyPayment,
            @NotNull LocalDate firstPaymentDate,
            DebtType debtType,
            @DecimalMin("0.01") @DecimalMax("1000000000") @Digits(integer = 10, fraction = 2) BigDecimal creditLimit,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal dailyRate,
            LocalDate interestStartDate) {
        public CreateLoan {
            if (name != null) name = name.strip();
            if (debtType == null) debtType = DebtType.LOAN;
        }
    }

    public record RecordPayment(
            @NotNull UUID requestId,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000000") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @PastOrPresent LocalDate paidOn) {
        public RecordPayment(UUID requestId, BigDecimal amount) { this(requestId, amount, null); }
    }
    public record LoanSummary(UUID id, String name, BigDecimal principal, BigDecimal balance, BigDecimal annualRate,
                              DebtType debtType) { }
    public record PaymentView(UUID id, int number, LocalDate date, BigDecimal amount, BigDecimal interest,
                              BigDecimal principal, BigDecimal balance, Instant recordedAt, LocalDate paidOn) { }
    public record LoanDetail(UUID id, String name, BigDecimal principal, BigDecimal annualRate,
                             BigDecimal monthlyPayment, LocalDate firstPaymentDate, BigDecimal balance,
                             BigDecimal principalPaid, BigDecimal interestPaid, BigDecimal totalPaid,
                             boolean paidOff, LocalDate nextPaymentDate, BigDecimal nextInterest,
                             BigDecimal suggestedPayment, BigDecimal maximumPayment, List<PaymentView> payments,
                             DebtType debtType, BigDecimal creditLimit, BigDecimal availableCredit,
                             BigDecimal dailyRate, LocalDate interestStartDate) { }
    public record Forecast(Plan baseline, Plan scenario, BigDecimal interestSaved, int monthsSaved) { }
}
