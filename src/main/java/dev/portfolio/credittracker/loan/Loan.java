package dev.portfolio.credittracker.loan;

import dev.portfolio.credittracker.domain.AmortizationCalculator.Terms;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "loans")
public class Loan {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, length = 80)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DebtType debtType;
    @Column(precision = 16, scale = 2)
    private BigDecimal creditLimit;
    @Column(precision = 7, scale = 4)
    private BigDecimal dailyRate;
    private LocalDate interestStartDate;
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal principal;
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal annualRate;
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal monthlyPayment;
    @Column(nullable = false)
    private LocalDate firstPaymentDate;
    @Column(nullable = false)
    private Instant createdAt;

    protected Loan() { }

    public Loan(String name, BigDecimal principal, BigDecimal annualRate,
                BigDecimal monthlyPayment, LocalDate firstPaymentDate, DebtType debtType,
                BigDecimal creditLimit, BigDecimal dailyRate, LocalDate interestStartDate) {
        this.name = name;
        this.principal = principal;
        this.annualRate = annualRate;
        this.monthlyPayment = monthlyPayment;
        this.firstPaymentDate = firstPaymentDate;
        this.createdAt = Instant.now();
        this.debtType = debtType;
        this.creditLimit = creditLimit;
        this.dailyRate = dailyRate;
        this.interestStartDate = interestStartDate;
    }

    public Terms terms() { return new Terms(principal, annualRate, monthlyPayment, firstPaymentDate, dailyRate, interestStartDate); }
    public DebtType getDebtType() { return debtType; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public BigDecimal getDailyRate() { return dailyRate; }
    public LocalDate getInterestStartDate() { return interestStartDate; }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getAnnualRate() { return annualRate; }
    public BigDecimal getMonthlyPayment() { return monthlyPayment; }
    public LocalDate getFirstPaymentDate() { return firstPaymentDate; }
}
