package dev.portfolio.credittracker.domain;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Fixed monthly model. One recorded payment represents one complete scheduled period. */
@Component
public class AmortizationCalculator {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000.00");
    public static final int MAX_MONTHS = 600;
    private static final BigDecimal RATE_DIVISOR = new BigDecimal("1200");

    public record Terms(BigDecimal principal, BigDecimal annualRate,
                        BigDecimal monthlyPayment, LocalDate firstPaymentDate) { }
    public record Row(int number, LocalDate date, BigDecimal openingBalance, BigDecimal payment,
                      BigDecimal interest, BigDecimal principal, BigDecimal balance) { }
    public record Plan(int months, LocalDate payoffDate, BigDecimal totalInterest,
                       BigDecimal totalPayment, List<Row> rows) { }
    public record Split(BigDecimal amount, BigDecimal interest, BigDecimal principal, BigDecimal balance) { }

    public BigDecimal interest(BigDecimal balance, BigDecimal annualRate) {
        // Divide once to avoid rounding the rate before calculating money.
        return balance.multiply(annualRate).divide(RATE_DIVISOR, 2, RoundingMode.HALF_UP);
    }

    public Plan plan(Terms terms, BigDecimal balance, int completedPeriods,
                     BigDecimal extraMonthly, BigDecimal oneTimeExtra) {
        checkMoney(extraMonthly, true);
        checkMoney(oneTimeExtra, true);
        BigDecimal remaining = balance.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal totalInterest = ZERO;
        BigDecimal totalPayment = ZERO;
        List<Row> rows = new ArrayList<>();
        while (remaining.signum() > 0) {
            if (rows.size() == MAX_MONTHS) {
                throw new CalculationException("Погашение занимает больше 600 месяцев. Увеличь ежемесячный платёж.");
            }
            BigDecimal fee = interest(remaining, terms.annualRate());
            BigDecimal budget = terms.monthlyPayment().add(extraMonthly)
                    .add(rows.isEmpty() ? oneTimeExtra : ZERO);
            if (budget.compareTo(fee) <= 0) {
                throw new CalculationException("Ежемесячный платёж должен быть больше процентов за месяц: " + fee + " ₽.");
            }
            BigDecimal payment = budget.min(remaining.add(fee)).setScale(2, RoundingMode.UNNECESSARY);
            BigDecimal principal = payment.subtract(fee);
            BigDecimal nextBalance = remaining.subtract(principal);
            int number = completedPeriods + rows.size() + 1;
            // Anchor to the original date: Jan 31 -> Feb 28/29 -> Mar 31.
            LocalDate date = terms.firstPaymentDate().plusMonths(number - 1L);
            rows.add(new Row(number, date, remaining, payment, fee, principal, nextBalance));
            totalInterest = totalInterest.add(fee);
            totalPayment = totalPayment.add(payment);
            remaining = nextBalance;
        }
        LocalDate payoffDate = rows.isEmpty()
                ? (completedPeriods == 0 ? null : terms.firstPaymentDate().plusMonths(completedPeriods - 1L))
                : rows.getLast().date();
        return new Plan(rows.size(), payoffDate, totalInterest, totalPayment, List.copyOf(rows));
    }

    public Split splitPayment(BigDecimal balance, BigDecimal annualRate, BigDecimal amount) {
        checkMoney(amount, false);
        if (balance.signum() == 0) throw new CalculationException("Кредит уже погашен.");
        BigDecimal fee = interest(balance, annualRate);
        if (amount.compareTo(fee) < 0) {
            throw new CalculationException("Платёж должен покрывать проценты за месяц: " + fee + " ₽.");
        }
        BigDecimal maximum = balance.add(fee);
        if (amount.compareTo(maximum) > 0) {
            throw new CalculationException("Для полного погашения достаточно " + maximum + " ₽. Уменьши платёж.");
        }
        BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal principal = normalized.subtract(fee);
        return new Split(normalized, fee, principal, balance.subtract(principal));
    }

    private void checkMoney(BigDecimal value, boolean allowZero) {
        if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0)
                || value.compareTo(MAX_AMOUNT) > 0 || value.scale() > 2) {
            throw new CalculationException("Сумма должна быть " + (allowZero ? "от 0" : "от 0.01")
                    + " до 1 000 000 000 ₽, не больше двух знаков после запятой.");
        }
    }
}
