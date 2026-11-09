package dev.portfolio.credittracker.budget;

import dev.portfolio.credittracker.domain.AmortizationCalculator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.portfolio.credittracker.domain.AmortizationCalculator.*;

@Component
public class RepaymentPlanner {
    public record Debt(UUID id, String name, Terms terms, BigDecimal balance, int completedPeriods, YearMonth lastPaidMonth) {}
    public record Inputs(BigDecimal income, BigDecimal expenses, BigDecimal firstMonthAvailable,
                         BigDecimal reserve, BigDecimal openingReserve, Map<YearMonth, BigDecimal> adjustments) {}
    public enum Strategy { HIGHEST_INTEREST, SMALLEST_BALANCE, MINIMUM_ONLY }
    public enum Status { COMPLETE, SHORTFALL, LIMIT, NO_DEBTS }
    public record Payment(String name, BigDecimal amount, BigDecimal extra, BigDecimal interest,
                          BigDecimal balance, LocalDate date, UUID debtId, int installment) {}
    public record Month(YearMonth month, BigDecimal minimum, BigDecimal total, BigDecimal remaining,
                        List<Payment> payments, BigDecimal reserveBalance, BigDecimal reserveUsed) {}
    public record Shortfall(YearMonth month, BigDecimal required, BigDecimal missing) {}
    public record Plan(BigDecimal income, BigDecimal expenses, BigDecimal available, Status status,
                       YearMonth payoffMonth, BigDecimal interest, Shortfall shortfall, List<Month> months) {}

    private static final class ActiveDebt {
        final Debt debt;
        final YearMonth firstMonth;
        BigDecimal balance;
        int periods;

        ActiveDebt(Debt debt, YearMonth start) {
            this.debt = debt;
            this.balance = debt.balance();
            this.periods = debt.completedPeriods();
            YearMonth next = YearMonth.from(debt.terms().firstPaymentDate()).plusMonths(periods);
            if (debt.lastPaidMonth() != null && !next.isAfter(debt.lastPaidMonth())) {
                next = debt.lastPaidMonth().plusMonths(1);
            }
            this.firstMonth = next.isBefore(start) ? start : next;
        }
    }

    private record Due(ActiveDebt debt, BigDecimal interest, BigDecimal minimum, BigDecimal maximum) {}

    private final AmortizationCalculator calculator;

    public RepaymentPlanner(AmortizationCalculator calculator) {
        this.calculator = calculator;
    }

    public Plan plan(List<Debt> debts, LocalDate today, Inputs inputs, Strategy strategy) {
        YearMonth start = YearMonth.from(today);
        BigDecimal income = inputs.income(), expenses = inputs.expenses();
        BigDecimal available = income.subtract(expenses);
        BigDecimal held = inputs.openingReserve();
        var active = new ArrayList<>(debts.stream().filter(debt -> debt.balance().signum() > 0)
                .map(debt -> new ActiveDebt(debt, start)).toList());
        var months = new ArrayList<Month>();
        BigDecimal interest = ZERO;
        if (active.isEmpty()) return new Plan(income, expenses, available, Status.NO_DEBTS, null, interest, null, List.of());

        for (int offset = 0; offset < MAX_MONTHS; offset++) {
            YearMonth month = start.plusMonths(offset);
            BigDecimal adjustment = inputs.adjustments().getOrDefault(month, ZERO);
            BigDecimal monthAvailable = offset == 0 ? inputs.firstMonthAvailable() : available.add(adjustment);
            BigDecimal cash = monthAvailable.add(held);
            var due = new ArrayList<Due>();
            BigDecimal minimum = ZERO;
            for (ActiveDebt debt : active) {
                if (month.isBefore(debt.firstMonth)) continue;
                BigDecimal fee = calculator.interest(debt.debt.terms(), debt.balance, debt.periods);
                BigDecimal maximum = debt.balance.add(fee);
                BigDecimal required = debt.debt.terms().monthlyPayment().max(fee).min(maximum);
                due.add(new Due(debt, fee, required, maximum));
                minimum = minimum.add(required);
            }
            if (due.isEmpty() && (strategy == Strategy.MINIMUM_ONLY
                    || inputs.reserve().signum() == 0 && adjustment.signum() == 0)) continue;
            if (strategy != Strategy.MINIMUM_ONLY && minimum.compareTo(cash) > 0) {
                return new Plan(income, expenses, available, Status.SHORTFALL, null, interest,
                        new Shortfall(month, minimum, minimum.subtract(cash)), List.copyOf(months));
            }

            // Compare interest per ruble without dividing or rounding the ratio.
            if (strategy == Strategy.SMALLEST_BALANCE) {
                due.sort(Comparator.comparing(payment -> payment.debt().balance));
            } else if (strategy == Strategy.HIGHEST_INTEREST) {
                due.sort((a, b) -> b.interest().multiply(a.debt().balance)
                        .compareTo(a.interest().multiply(b.debt().balance)));
            }
            BigDecimal afterMinimum = cash.subtract(minimum);
            BigDecimal extra = strategy == Strategy.MINIMUM_ONLY ? ZERO : afterMinimum.subtract(inputs.reserve()).max(ZERO);
            BigDecimal reserveUsed = held.subtract(afterMinimum).max(ZERO);
            BigDecimal total = ZERO;
            var paid = new ArrayList<Payment>();
            for (Due payment : due) {
                BigDecimal overpayment = extra.min(payment.maximum().subtract(payment.minimum()));
                BigDecimal amount = payment.minimum().add(overpayment);
                ActiveDebt debt = payment.debt();
                debt.balance = debt.balance.subtract(amount.subtract(payment.interest()));
                debt.periods++;
                extra = extra.subtract(overpayment);
                interest = interest.add(payment.interest());
                total = total.add(amount);
                paid.add(new Payment(debt.debt.name(), amount, overpayment, payment.interest(), debt.balance,
                        date(month, debt, today), debt.debt.id(), debt.periods));
            }
            active.removeIf(debt -> debt.balance.signum() == 0);
            BigDecimal remaining = cash.subtract(total);
            held = remaining.max(ZERO).min(inputs.reserve());
            months.add(new Month(month, minimum, total, remaining.subtract(held), List.copyOf(paid), held,
                    strategy == Strategy.MINIMUM_ONLY ? ZERO : reserveUsed));
            if (active.isEmpty()) {
                return new Plan(income, expenses, available, Status.COMPLETE, month, interest, null, List.copyOf(months));
            }
        }
        return new Plan(income, expenses, available, Status.LIMIT, null, interest, null, List.copyOf(months));
    }

    private LocalDate date(YearMonth month, ActiveDebt debt, LocalDate today) {
        int day = debt.debt.terms().firstPaymentDate().getDayOfMonth();
        LocalDate date = month.atDay(Math.min(day, month.lengthOfMonth()));
        return date.isBefore(today) ? today : date;
    }
}
