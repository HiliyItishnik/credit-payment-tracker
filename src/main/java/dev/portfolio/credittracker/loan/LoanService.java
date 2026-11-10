package dev.portfolio.credittracker.loan;

import dev.portfolio.credittracker.api.ApiException;
import dev.portfolio.credittracker.api.LoanDtos.*;
import dev.portfolio.credittracker.domain.AmortizationCalculator;
import dev.portfolio.credittracker.domain.CalculationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
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
        validateType(request);
        Loan loan = new Loan(request.name(), request.principal().setScale(2),
                request.debtType() == DebtType.MICROLOAN ? ZERO : request.annualRate(),
                request.monthlyPayment().setScale(2), request.firstPaymentDate(), request.debtType(),
                request.creditLimit(), request.dailyRate(), request.interestStartDate());
        calculator.plan(loan.terms(), loan.getPrincipal(), 0, ZERO, ZERO);
        return detail(loans.save(loan), List.of());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<LoanSummary> list() {
        var latest = payments.findLatestForAllLoans().stream()
                .collect(Collectors.toMap(PaymentRepository.LatestPayment::getLoanId, payment -> payment));
        return loans.findAllByOrderByCreatedAtAsc().stream().map(loan -> {
            var payment = latest.get(loan.getId());
            BigDecimal balance = payment == null ? loan.getPrincipal() : payment.getBalance();
            return new LoanSummary(loan.getId(), loan.getName(), loan.getPrincipal(), balance, loan.getAnnualRate(), loan.getDebtType());
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
            if (existing.get().getAmount().compareTo(request.amount()) != 0
                    || request.paidOn() != null && !request.paidOn().equals(existing.get().getPaidOn())) {
                throw new ApiException(HttpStatus.CONFLICT, "Этот requestId уже использован для другой суммы или даты.");
            }
            return view(existing.get());
        }
        List<Payment> history = history(id);
        var split = calculator.splitPayment(loan.terms(), balance(loan, history), history.size(), request.amount());
        if (request.paidOn() != null && request.paidOn().getYear() < 1900) {
            throw new CalculationException("Дата оплаты должна быть не раньше 1900 года.");
        }
        return view(payments.saveAndFlush(new Payment(loan, request.requestId(), history.size() + 1, split, request.paidOn())));
    }

    @Transactional
    public void delete(UUID id) {
        loans.findLockedById(id).ifPresent(loan -> {
            payments.deleteAllForLoan(id);
            loans.delete(loan);
        });
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
    private void validateType(CreateLoan request) {
        if (request.debtType() == DebtType.MICROLOAN) {
            if (request.dailyRate() == null || request.interestStartDate() == null) {
                throw new CalculationException("Для микрозайма укажи дневную ставку и дату начала начисления процентов.");
            }
            if (request.interestStartDate().isBefore(LocalDate.of(1900, 1, 1))
                    || !request.interestStartDate().isBefore(request.firstPaymentDate())) {
                throw new CalculationException("Начало начисления процентов должно быть не раньше 1900 года и раньше первого платежа.");
            }
            if (request.annualRate() != null && request.annualRate().signum() != 0) {
                throw new CalculationException("Для микрозайма используй дневную ставку вместо годовой.");
            }
        } else {
            if (request.annualRate() == null) throw new CalculationException("Укажи годовую ставку.");
            if (request.dailyRate() != null || request.interestStartDate() != null) {
                throw new CalculationException("Дневная ставка и начало начисления доступны только для микрозайма.");
            }
        }
        if (request.debtType() == DebtType.CREDIT_CARD) {
            if (request.creditLimit() == null || request.creditLimit().compareTo(request.principal()) < 0) {
                throw new CalculationException("Кредитный лимит должен быть не меньше текущего долга по карте.");
            }
        } else if (request.creditLimit() != null) {
            throw new CalculationException("Кредитный лимит доступен только для кредитной карты.");
        }
    }
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
        BigDecimal fee = paidOff ? ZERO : calculator.interest(loan.terms(), balance, history.size());
        BigDecimal maximum = balance.add(fee);
        return new LoanDetail(loan.getId(), loan.getName(), loan.getPrincipal(), loan.getAnnualRate(),
                loan.getMonthlyPayment(), loan.getFirstPaymentDate(), balance, loan.getPrincipal().subtract(balance),
                interestPaid, totalPaid, paidOff,
                paidOff ? null : loan.getFirstPaymentDate().plusMonths(history.size()), fee,
                loan.getMonthlyPayment().min(maximum), maximum, history.stream().map(this::view).toList(),
                loan.getDebtType(), loan.getCreditLimit(),
                loan.getCreditLimit() == null ? null : loan.getCreditLimit().subtract(balance),
                loan.getDailyRate(), loan.getInterestStartDate());
    }
    private PaymentView view(Payment p) {
        return new PaymentView(p.getId(), p.getInstallmentNumber(), p.getDueDate(), p.getAmount(),
                p.getInterest(), p.getPrincipal(), p.getBalance(), p.getRecordedAt(), p.getPaidOn());
    }
}
