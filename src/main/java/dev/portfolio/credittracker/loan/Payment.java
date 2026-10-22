package dev.portfolio.credittracker.loan;

import dev.portfolio.credittracker.domain.AmortizationCalculator.Split;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false)
    private Loan loan;
    @Column(nullable = false)
    private UUID requestId;
    @Column(nullable = false)
    private int installmentNumber;
    @Column(nullable = false)
    private LocalDate dueDate;
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal interest;
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal principal;
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal balance;
    @Column(nullable = false)
    private Instant recordedAt;

    protected Payment() { }

    public Payment(Loan loan, UUID requestId, int installmentNumber, Split split) {
        this.loan = loan;
        this.requestId = requestId;
        this.installmentNumber = installmentNumber;
        this.dueDate = loan.getFirstPaymentDate().plusMonths(installmentNumber - 1L);
        this.amount = split.amount();
        this.interest = split.interest();
        this.principal = split.principal();
        this.balance = split.balance();
        this.recordedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public int getInstallmentNumber() { return installmentNumber; }
    public LocalDate getDueDate() { return dueDate; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getInterest() { return interest; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getBalance() { return balance; }
    public Instant getRecordedAt() { return recordedAt; }
}
