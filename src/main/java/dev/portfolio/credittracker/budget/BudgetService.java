package dev.portfolio.credittracker.budget;

import dev.portfolio.credittracker.loan.LoanRepository;
import dev.portfolio.credittracker.loan.PaymentRepository;
import dev.portfolio.credittracker.domain.CalculationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static dev.portfolio.credittracker.budget.BudgetController.*;
import static dev.portfolio.credittracker.domain.AmortizationCalculator.ZERO;
import static dev.portfolio.credittracker.domain.AmortizationCalculator.MAX_MONTHS;
import static dev.portfolio.credittracker.budget.RepaymentPlanner.Strategy.*;

@Service
public class BudgetService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final LoanRepository loans;
    private final PaymentRepository payments;
    private final RepaymentPlanner planner;

    public BudgetService(JdbcClient jdbc, ObjectMapper json, LoanRepository loans,
                         PaymentRepository payments, RepaymentPlanner planner) {
        this.jdbc = jdbc;
        this.json = json;
        this.loans = loans;
        this.payments = payments;
        this.planner = planner;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview get() {
        return overview(loadBudget());
    }

    @Transactional
    public Overview save(Budget budget) {
        jdbc.sql("""
                INSERT INTO monthly_budget(id, entries) VALUES (1, CAST(:entries AS jsonb))
                ON CONFLICT (id) DO UPDATE SET entries = EXCLUDED.entries
                """).param("entries", json.writeValueAsString(budget)).update();
        return overview(budget);
    }

    private Overview overview(Budget budget) {
        LocalDate today = LocalDate.now();
        var debts = activeDebts();
        var totals = plannedTotals(budget);
        BigDecimal available = totals.income().subtract(totals.expenses()).subtract(totalPaid(YearMonth.from(today)));
        return new Overview(budget,
                planner.plan(debts, today, totals.income(), totals.expenses(), available, HIGHEST_INTEREST),
                planner.plan(debts, today, totals.income(), totals.expenses(), available, SMALLEST_BALANCE));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CashCalendar.Result calendar(YearMonth month, BigDecimal openingBalance) {
        LocalDate today = LocalDate.now();
        validateMonth(month);
        if (month.isBefore(YearMonth.from(today))) {
            throw new CalculationException("Выбери текущий или будущий месяц для календаря.");
        }
        if (openingBalance.signum() < 0 || openingBalance.scale() > 2
                || openingBalance.compareTo(new BigDecimal("50000000000")) > 0) {
            throw new CalculationException("Остаток должен быть от 0 до 50 млрд ₽ с точностью до копеек.");
        }
        Budget budget = loadBudget();
        var events = new ArrayList<>(paidEvents(month));
        var totals = plannedTotals(budget);
        var plan = planner.plan(activeDebts(), today, totals.income(), totals.expenses(), ZERO, MINIMUM_ONLY);
        for (var planned : plan.months()) {
            if (!planned.month().equals(month)) continue;
            for (var payment : planned.payments()) {
                events.add(new CashCalendar.Event(payment.date(), CashCalendar.Kind.PAYMENT, payment.name(),
                        payment.amount()));
            }
        }
        boolean incomplete = plan.status() == RepaymentPlanner.Status.LIMIT
                && !month.isBefore(YearMonth.from(today).plusMonths(MAX_MONTHS));
        return CashCalendar.build(month, openingBalance, budget, events, incomplete);
    }

    private record SavedMonth(Actual actual, Totals planned) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MonthReview review(YearMonth month) {
        return monthReview(month, null);
    }

    @Transactional
    public MonthReview saveActual(YearMonth month, Actual actual) {
        return monthReview(month, actual);
    }

    private MonthReview monthReview(YearMonth month, Actual submitted) {
        validateMonth(month);
        LocalDate today = LocalDate.now();
        YearMonth current = YearMonth.from(today);
        if (month.isAfter(current)) throw new CalculationException("Факт можно внести за текущий или прошедший месяц.");
        var saved = jdbc.sql("SELECT actual::text, planned::text FROM budget_actuals WHERE month = :month")
                .param("month", month.atDay(1))
                .query((rs, row) -> new SavedMonth(json.readValue(rs.getString(1), Actual.class),
                        json.readValue(rs.getString(2), Totals.class))).optional();
        var totals = plannedTotals(loadBudget());
        BigDecimal paid = totalPaid(month);
        boolean currentMonth = month.equals(current);
        var debts = currentMonth ? activeDebts() : List.<RepaymentPlanner.Debt>of();
        var baseline = currentMonth ? baseline(totals, today, debts, paid) : null;
        Totals planned = saved.map(SavedMonth::planned).orElseGet(() -> new Totals(totals.income(), totals.expenses(),
                currentMonth ? scheduledThisMonth(baseline, current).add(paid) : null));
        Actual actual = submitted == null ? saved.map(SavedMonth::actual).orElse(null) : submitted;
        if (submitted != null) {
            String stored = jdbc.sql("""
                    INSERT INTO budget_actuals(month, actual, planned)
                    VALUES (:month, CAST(:actual AS jsonb), CAST(:planned AS jsonb))
                    ON CONFLICT (month) DO UPDATE SET actual = EXCLUDED.actual
                    RETURNING planned::text
                    """).param("month", month.atDay(1))
                    .param("actual", json.writeValueAsString(submitted))
                    .param("planned", json.writeValueAsString(planned))
                    .query(String.class).single();
            planned = json.readValue(stored, Totals.class);
        }
        Forecast adjusted = null;
        if (currentMonth && actual != null) {
            BigDecimal remaining = actual.income().subtract(actual.expenses()).subtract(paid);
            adjusted = new Forecast(planner.plan(debts, today, totals.income(), totals.expenses(), remaining, HIGHEST_INTEREST));
        }
        return new MonthReview(month, planned, actual, paid, currentMonth ? new Forecast(baseline) : null, adjusted);
    }

    private RepaymentPlanner.Plan baseline(Totals totals, LocalDate today, List<RepaymentPlanner.Debt> debts, BigDecimal paid) {
        return planner.plan(debts, today, totals.income(), totals.expenses(),
                totals.income().subtract(totals.expenses()).subtract(paid), HIGHEST_INTEREST);
    }

    private BigDecimal scheduledThisMonth(RepaymentPlanner.Plan plan, YearMonth month) {
        if (plan.shortfall() != null && plan.shortfall().month().equals(month)) return plan.shortfall().required();
        return plan.months().stream().filter(entry -> entry.month().equals(month)).findFirst()
                .map(RepaymentPlanner.Month::total).orElse(ZERO);
    }

    private List<RepaymentPlanner.Debt> activeDebts() {
        return loans.findAllByOrderByCreatedAtAsc().stream().map(loan -> {
            var last = payments.findFirstByLoanIdOrderByInstallmentNumberDesc(loan.getId());
            return new RepaymentPlanner.Debt(loan.getName(), loan.terms(),
                    last.map(payment -> payment.getBalance()).orElse(loan.getPrincipal()),
                    last.map(payment -> payment.getInstallmentNumber()).orElse(0),
                    last.map(payment -> YearMonth.from(payment.getRecordedAt().atZone(ZoneId.systemDefault()))).orElse(null));
        }).toList();
    }

    private BigDecimal totalPaid(YearMonth month) {
        return paidEvents(month).stream().map(CashCalendar.Event::amount).reduce(ZERO, BigDecimal::add);
    }

    private List<CashCalendar.Event> paidEvents(YearMonth month) {
        ZoneId zone = ZoneId.systemDefault();
        return jdbc.sql("""
                SELECT p.recorded_at, p.amount, l.name FROM payments p
                JOIN loans l ON l.id = p.loan_id
                WHERE p.recorded_at >= :start AND p.recorded_at < :end
                ORDER BY p.recorded_at
                """)
                .param("start", month.atDay(1).atStartOfDay(zone).toOffsetDateTime())
                .param("end", month.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime())
                .query((rs, row) -> new CashCalendar.Event(
                        rs.getObject(1, OffsetDateTime.class).atZoneSameInstant(zone).toLocalDate(),
                        CashCalendar.Kind.RECORDED_PAYMENT, rs.getString(3), rs.getBigDecimal(2))).list();
    }

    private void validateMonth(YearMonth month) {
        if (month.getYear() < 1900 || month.getYear() > 2200) {
            throw new CalculationException("Выбери месяц с 1900 по 2200 год.");
        }
    }

    private Budget loadBudget() {
        return jdbc.sql("SELECT entries::text FROM monthly_budget WHERE id = 1")
                .query(String.class).optional().map(value -> json.readValue(value, Budget.class))
                .orElseGet(() -> new Budget(List.of(), List.of()));
    }

    private Totals plannedTotals(Budget budget) {
        return new Totals(total(budget.incomes()), total(budget.expenses()), null);
    }

    private BigDecimal total(List<Entry> entries) {
        return entries.stream().map(Entry::amount).reduce(ZERO, BigDecimal::add);
    }
}
