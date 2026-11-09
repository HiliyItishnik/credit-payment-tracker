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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        if (budget.openingReserve().compareTo(budget.reserve()) > 0) {
            throw new CalculationException("Резерв на начало месяца не может превышать целевую сумму.");
        }
        for (var entry : budget.oneOffs()) validateMonth(YearMonth.from(entry.date()));
        jdbc.sql("""
                INSERT INTO monthly_budget(id, entries) VALUES (1, CAST(:entries AS jsonb))
                ON CONFLICT (id) DO UPDATE SET entries = EXCLUDED.entries
                """).param("entries", json.writeValueAsString(budget)).update();
        return overview(budget);
    }

    private Overview overview(Budget budget) {
        LocalDate today = LocalDate.now();
        var debts = activeDebts();
        YearMonth month = YearMonth.from(today);
        var totals = plannedTotals(budget, month);
        BigDecimal available = totals.income().subtract(totals.expenses()).subtract(totalPaid(month));
        var inputs = inputs(budget, available);
        return new Overview(budget,
                planner.plan(debts, today, inputs, HIGHEST_INTEREST),
                planner.plan(debts, today, inputs, SMALLEST_BALANCE));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CashCalendar.Result calendar(YearMonth month, BigDecimal openingBalance) {
        LocalDate today = LocalDate.now();
        validateMonth(month);
        if (month.isBefore(YearMonth.from(today))) {
            throw new CalculationException("Выбери текущий или будущий месяц для календаря.");
        }
        validateBalance(openingBalance);
        Budget budget = loadBudget();
        var events = new ArrayList<>(paidEvents(month));
        var plan = minimumSchedule(today);
        for (var planned : plan.months()) {
            if (!planned.month().equals(month)) continue;
            for (var payment : planned.payments()) {
                events.add(CashCalendar.debtEvent(payment));
            }
        }
        boolean incomplete = plan.status() == RepaymentPlanner.Status.LIMIT
                && !month.isBefore(YearMonth.from(today).plusMonths(MAX_MONTHS));
        return CashCalendar.build(month, openingBalance, budget, events, incomplete);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CashCalendar.PaydayPlan untilPayday(BigDecimal balance) {
        validateBalance(balance);
        LocalDate today = LocalDate.now();
        return CashCalendar.untilPayday(today, balance, loadBudget(), minimumSchedule(today).months());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DebtOverview debtOverview() {
        LocalDate today = LocalDate.now();
        YearMonth current = YearMonth.from(today);
        YearMonth start = current.minusMonths(2);
        var debts = activeDebts();
        BigDecimal balance = debts.stream().map(RepaymentPlanner.Debt::balance).reduce(ZERO, BigDecimal::add);
        BigDecimal due = minimumSchedule(today, debts).months().stream()
                .filter(month -> month.month().equals(current)).findFirst()
                .map(RepaymentPlanner.Month::total).orElse(ZERO);
        var amounts = new HashMap<YearMonth, BigDecimal>();
        jdbc.sql("""
                SELECT CAST(date_trunc('month', pay_date) AS date), SUM(principal)
                FROM (
                    SELECT COALESCE(paid_on, CAST(recorded_at AT TIME ZONE :zone AS date)) AS pay_date, principal
                    FROM payments
                ) history
                WHERE pay_date >= :start AND pay_date < :end
                GROUP BY 1
                """).param("zone", ZoneId.systemDefault().getId())
                .param("start", start.atDay(1)).param("end", current.plusMonths(1).atDay(1))
                .query((rs, row) -> new PrincipalPaid(YearMonth.from(rs.getObject(1, LocalDate.class)), rs.getBigDecimal(2)))
                .list().forEach(row -> amounts.put(row.month(), row.amount()));
        var principalPaid = new ArrayList<PrincipalPaid>();
        for (YearMonth month = start; !month.isAfter(current); month = month.plusMonths(1)) {
            principalPaid.add(new PrincipalPaid(month, amounts.getOrDefault(month, ZERO)));
        }
        return new DebtOverview(current, balance, due, List.copyOf(principalPaid));
    }

    private void validateBalance(BigDecimal balance) {
        if (balance.signum() < 0 || balance.scale() > 2 || balance.compareTo(new BigDecimal("50000000000")) > 0) {
            throw new CalculationException("Остаток должен быть от 0 до 50 млрд ₽ с точностью до копеек.");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String reminders(int days) {
        LocalDate today = LocalDate.now();
        YearMonth end = YearMonth.from(today).plusMonths(12);
        var payments = minimumSchedule(today).months().stream().takeWhile(month -> month.month().isBefore(end))
                .flatMap(month -> month.payments().stream()).toList();
        return ReminderCalendar.build(payments, days);
    }

    private RepaymentPlanner.Plan minimumSchedule(LocalDate today) {
        return minimumSchedule(today, activeDebts());
    }

    private RepaymentPlanner.Plan minimumSchedule(LocalDate today, List<RepaymentPlanner.Debt> debts) {
        return planner.plan(debts, today, new RepaymentPlanner.Inputs(ZERO, ZERO, ZERO, ZERO, ZERO, Map.of()), MINIMUM_ONLY);
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
        Budget budget = loadBudget();
        var totals = plannedTotals(budget, month);
        BigDecimal paid = totalPaid(month);
        boolean currentMonth = month.equals(current);
        var debts = currentMonth ? activeDebts() : List.<RepaymentPlanner.Debt>of();
        var baseline = currentMonth ? planner.plan(debts, today,
                inputs(budget, totals.income().subtract(totals.expenses()).subtract(paid)), HIGHEST_INTEREST) : null;
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
            adjusted = new Forecast(planner.plan(debts, today, inputs(budget, remaining), HIGHEST_INTEREST));
        }
        return new MonthReview(month, planned, actual, paid, currentMonth ? new Forecast(baseline) : null, adjusted);
    }

    private RepaymentPlanner.Inputs inputs(Budget budget, BigDecimal firstMonthAvailable) {
        var adjustments = new HashMap<YearMonth, BigDecimal>();
        for (var entry : budget.oneOffs()) {
            var amount = entry.direction() == Direction.INCOME ? entry.amount() : entry.amount().negate();
            adjustments.merge(YearMonth.from(entry.date()), amount, BigDecimal::add);
        }
        return new RepaymentPlanner.Inputs(total(budget.incomes()), total(budget.expenses()), firstMonthAvailable,
                budget.reserve(), budget.openingReserve(), adjustments);
    }

    private BigDecimal scheduledThisMonth(RepaymentPlanner.Plan plan, YearMonth month) {
        if (plan.shortfall() != null && plan.shortfall().month().equals(month)) return plan.shortfall().required();
        return plan.months().stream().filter(entry -> entry.month().equals(month)).findFirst()
                .map(RepaymentPlanner.Month::total).orElse(ZERO);
    }

    private List<RepaymentPlanner.Debt> activeDebts() {
        var lastPaidDates = new HashMap<UUID, YearMonth>();
        jdbc.sql("""
                SELECT loan_id, MAX(COALESCE(paid_on, CAST(recorded_at AT TIME ZONE :zone AS date)))
                FROM payments GROUP BY loan_id
                """).param("zone", ZoneId.systemDefault().getId())
                .query((rs, row) -> Map.entry(rs.getObject(1, UUID.class), YearMonth.from(rs.getObject(2, LocalDate.class))))
                .list().forEach(entry -> lastPaidDates.put(entry.getKey(), entry.getValue()));
        return loans.findAllByOrderByCreatedAtAsc().stream().map(loan -> {
            var last = payments.findFirstByLoanIdOrderByInstallmentNumberDesc(loan.getId());
            return new RepaymentPlanner.Debt(loan.getId(), loan.getName(), loan.terms(),
                    last.map(payment -> payment.getBalance()).orElse(loan.getPrincipal()),
                    last.map(payment -> payment.getInstallmentNumber()).orElse(0),
                    lastPaidDates.get(loan.getId()));
        }).toList();
    }

    private BigDecimal totalPaid(YearMonth month) {
        return paidEvents(month).stream().map(CashCalendar.Event::amount).reduce(ZERO, BigDecimal::add);
    }

    private List<CashCalendar.Event> paidEvents(YearMonth month) {
        return jdbc.sql("""
                SELECT pay_date, amount, name FROM (
                    SELECT COALESCE(p.paid_on, CAST(p.recorded_at AT TIME ZONE :zone AS date)) AS pay_date,
                           p.amount, l.name, p.recorded_at
                    FROM payments p JOIN loans l ON l.id = p.loan_id
                ) events
                WHERE pay_date >= :start AND pay_date < :end
                ORDER BY pay_date, recorded_at
                """)
                .param("zone", ZoneId.systemDefault().getId())
                .param("start", month.atDay(1))
                .param("end", month.plusMonths(1).atDay(1))
                .query((rs, row) -> new CashCalendar.Event(
                        rs.getObject(1, LocalDate.class),
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
                .orElseGet(() -> new Budget(List.of(), List.of(), List.of(), ZERO, ZERO));
    }

    private Totals plannedTotals(Budget budget, YearMonth month) {
        BigDecimal income = total(budget.incomes()), expenses = total(budget.expenses());
        for (var entry : budget.oneOffs()) {
            if (!YearMonth.from(entry.date()).equals(month)) continue;
            if (entry.direction() == Direction.INCOME) income = income.add(entry.amount());
            else expenses = expenses.add(entry.amount());
        }
        return new Totals(income, expenses, null);
    }

    private BigDecimal total(List<Entry> entries) {
        return entries.stream().map(Entry::amount).reduce(ZERO, BigDecimal::add);
    }
}
