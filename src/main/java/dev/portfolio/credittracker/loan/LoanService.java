package dev.portfolio.credittracker.loan;

import dev.portfolio.credittracker.api.ApiException;
import dev.portfolio.credittracker.api.LoanDtos.*;
import dev.portfolio.credittracker.domain.AmortizationCalculator;
import dev.portfolio.credittracker.domain.CalculationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static dev.portfolio.credittracker.domain.AmortizationCalculator.ZERO;

@Service
public class LoanService {
    private final LoanRepository loans;
    private final PaymentRepository payments;
    private final AmortizationCalculator calculator;

    public LoanService(LoanRepository loans, PaymentRepository payments, AmortizationCalculator calculator) {
        this.loans = loans;
        this.payments = payments;
        this.calculator = calculator;
    }

    @Transactional
    public LoanDetail create(CreateLoan request) {
        if (request.firstPaymentDate().isBefore(LocalDate.of(1900, 1, 1))
                || request.firstPaymentDate().isAfter(LocalDate.of(2200, 12, 31))) {
            throw new CalculationException("Дата первого платежа должна быть между 1900 и 2200 годом.");
        }
        Loan loan = new Loan(request.name(), request.principal().setScale(2), request.annualRate(),
                request.monthlyPayment().setScale(2), request.firstPaymentDate());
        calculator.plan(loan.terms(), loan.getPrincipal(), 0, ZERO, ZERO);
        return detail(loans.save(loan), List.of());
    }

    @Transactional(readOnly = true)
    public List<LoanSummary> list() {
        return loans.findAllByOrderByCreatedAtAsc().stream().map(loan -> {
            BigDecimal balance = payments.findFirstByLoanIdOrderByInstallmentNumberDesc(loan.getId())
                    .map(Payment::getBalance).orElse(loan.getPrincipal());
            return new LoanSummary(loan.getId(), loan.getName(), loan.getPrincipal(), balance, loan.getAnnualRate());
        }).toList();
    }

    @Transactional(readOnly = true)
    public LoanDetail get(UUID id) {
        return detail(find(id), history(id));
    }

    @Transactional(readOnly = true)
    public Forecast forecast(UUID id, BigDecimal extraMonthly, BigDecimal oneTimeExtra) {
        Loan loan = find(id);
        List<Payment> history = history(id);
        BigDecimal balance = balance(loan, history);
        var baseline = calculator.plan(loan.terms(), balance, history.size(), ZERO, ZERO);
        var scenario = calculator.plan(loan.terms(), balance, history.size(), extraMonthly, oneTimeExtra);
        return new Forecast(baseline, scenario, baseline.totalInterest().subtract(scenario.totalInterest()),
                baseline.months() - scenario.months());
    }

    @Transactional
    public PaymentView record(UUID id, RecordPayment request) {
        // Serialize mutations for this loan. Different loans can still be updated independently.
        Loan loan = loans.findLockedById(id).orElseThrow(this::notFound);
        var existing = payments.findByLoanIdAndRequestId(id, request.requestId());
        if (existing.isPresent()) {
            if (existing.get().getAmount().compareTo(request.amount()) != 0) {
                throw new ApiException(HttpStatus.CONFLICT, "Этот requestId уже использован для другой суммы.");
            }
            return view(existing.get());
        }
        List<Payment> history = history(id);
        var split = calculator.splitPayment(balance(loan, history), loan.getAnnualRate(), request.amount());
        return view(payments.saveAndFlush(new Payment(loan, request.requestId(), history.size() + 1, split)));
    }

    @Transactional
    public void undo(UUID id, UUID paymentId) {
        loans.findLockedById(id).orElseThrow(this::notFound);
        Payment last = payments.findFirstByLoanIdOrderByInstallmentNumberDesc(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "История платежей пуста."));
        if (!last.getId().equals(paymentId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Можно отменить только последний платёж. Обнови историю.");
        }
        payments.delete(last);
    }

    private Loan find(UUID id) { return loans.findById(id).orElseThrow(this::notFound); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "Кредит не найден."); }
    private List<Payment> history(UUID id) { return payments.findByLoanIdOrderByInstallmentNumberAsc(id); }
    private BigDecimal balance(Loan loan, List<Payment> history) {
        return history.isEmpty() ? loan.getPrincipal() : history.getLast().getBalance();
    }
    private LoanDetail detail(Loan loan, List<Payment> history) {
        BigDecimal balance = balance(loan, history);
        BigDecimal interestPaid = history.stream().map(Payment::getInterest).reduce(ZERO, BigDecimal::add);
        BigDecimal totalPaid = history.stream().map(Payment::getAmount).reduce(ZERO, BigDecimal::add);
        boolean paidOff = balance.signum() == 0;
        BigDecimal fee = paidOff ? ZERO : calculator.interest(balance, loan.getAnnualRate());
        BigDecimal maximum = balance.add(fee);
        return new LoanDetail(loan.getId(), loan.getName(), loan.getPrincipal(), loan.getAnnualRate(),
                loan.getMonthlyPayment(), loan.getFirstPaymentDate(), balance, loan.getPrincipal().subtract(balance),
                interestPaid, totalPaid, paidOff,
                paidOff ? null : loan.getFirstPaymentDate().plusMonths(history.size()), fee,
                loan.getMonthlyPayment().min(maximum), maximum, history.stream().map(this::view).toList());
    }
    private PaymentView view(Payment p) {
        return new PaymentView(p.getId(), p.getInstallmentNumber(), p.getDueDate(), p.getAmount(),
                p.getInterest(), p.getPrincipal(), p.getBalance(), p.getRecordedAt());
    }
}
