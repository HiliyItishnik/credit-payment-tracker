package dev.portfolio.credittracker.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.portfolio.credittracker.budget.BudgetController.Budget;
import static dev.portfolio.credittracker.domain.AmortizationCalculator.MAX_MONTHS;
import static dev.portfolio.credittracker.domain.AmortizationCalculator.ZERO;

final class CashCalendar {
    enum Kind { INCOME, EXPENSE, PAYMENT, RECORDED_PAYMENT }
    record Event(LocalDate date, Kind kind, String name, BigDecimal amount, UUID entryId) {
        Event(LocalDate date, Kind kind, String name, BigDecimal amount) {
            this(date, kind, name, amount, null);
        }
    }
    record Occurrence(UUID entryId, YearMonth month) {}
    record Row(Event event, BigDecimal balance, boolean completed) {}
    record Gap(LocalDate date, BigDecimal missing) {}
    record Result(YearMonth month, BigDecimal openingBalance, List<Row> rows, Gap firstGap,
                  int undatedEntries, boolean incompleteDebtSchedule, BigDecimal reserve, Gap reserveGap) {}
    record PaydayPlan(LocalDate today, LocalDate nextIncomeDate, List<Event> obligations,
                      BigDecimal required, BigDecimal remaining, BigDecimal reserve, BigDecimal extra,
                      int undatedExpenses, boolean incompleteDebtSchedule) {}

    static Result build(YearMonth month, BigDecimal opening, Budget budget, List<Event> debtEvents,
                        boolean incompleteDebtSchedule, Set<Occurrence> marks) {
        var events = new ArrayList<>(debtEvents);
        events.addAll(budgetEvents(month, budget));
        int undated = undatedCount(budget.incomes()) + undatedCount(budget.expenses());
        events.sort(Comparator.comparing(Event::date).thenComparing(Event::kind));
        BigDecimal balance = opening;
        Gap gap = null;
        Gap reserveGap = null;
        var rows = new ArrayList<Row>();
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            balance = event.kind() == Kind.INCOME ? balance.add(event.amount()) : balance.subtract(event.amount());
            rows.add(new Row(event, balance, completed(event, marks)));
            boolean endOfDay = i + 1 == events.size() || !events.get(i + 1).date().equals(event.date());
            if (endOfDay && gap == null && balance.compareTo(ZERO) < 0) gap = new Gap(event.date(), balance.negate());
            if (endOfDay && reserveGap == null && budget.reserve().signum() > 0 && balance.compareTo(budget.reserve()) < 0) {
                reserveGap = new Gap(event.date(), budget.reserve().subtract(balance));
            }
        }
        return new Result(month, opening, List.copyOf(rows), gap, undated, incompleteDebtSchedule, budget.reserve(), reserveGap);
    }

    static PaydayPlan untilPayday(LocalDate today, BigDecimal balance, Budget budget,
                                  List<RepaymentPlanner.Month> schedule, Set<Occurrence> marks) {
        YearMonth start = YearMonth.from(today);
        var incomes = new ArrayList<Event>();
        for (var entry : budget.incomes()) {
            if (entry.day() == null) continue;
            YearMonth month = start;
            LocalDate date = dayOfMonth(month, entry.day());
            while (!date.isAfter(today) || marks.contains(new Occurrence(entry.id(), month))) {
                month = month.plusMonths(1);
                date = dayOfMonth(month, entry.day());
            }
            incomes.add(new Event(date, Kind.INCOME, entry.name(), entry.amount(), entry.id()));
        }
        budget.oneOffs().stream().filter(entry -> entry.direction() == BudgetController.Direction.INCOME)
                .map(CashCalendar::oneOffEvent).forEach(incomes::add);
        LocalDate payday = incomes.stream().filter(event -> !completed(event, marks))
                .map(Event::date).filter(date -> date.isAfter(today))
                .min(Comparator.naturalOrder()).orElse(null);
        int undated = undatedCount(budget.expenses());
        if (payday == null) {
            return new PaydayPlan(today, null, List.of(), null, null, budget.reserve(), null, undated, false);
        }

        boolean incomplete = !YearMonth.from(payday).isBefore(start.plusMonths(MAX_MONTHS));
        if (incomplete) {
            return new PaydayPlan(today, payday, List.of(), null, null, budget.reserve(), null, undated, true);
        }
        var upcoming = new ArrayList<Event>();
        for (YearMonth month = start; !month.isAfter(YearMonth.from(payday)); month = month.plusMonths(1)) {
            addEntries(upcoming, month, budget.expenses(), Kind.EXPENSE);
        }
        budget.oneOffs().stream().filter(entry -> entry.direction() == BudgetController.Direction.EXPENSE)
                .map(CashCalendar::oneOffEvent).forEach(upcoming::add);
        schedule.stream().flatMap(month -> month.payments().stream()).map(CashCalendar::debtEvent).forEach(upcoming::add);
        var obligations = upcoming.stream()
                .filter(event -> !event.date().isBefore(today) && event.date().isBefore(payday))
                .filter(event -> !completed(event, marks))
                .sorted(Comparator.comparing(Event::date).thenComparing(Event::kind)).toList();
        BigDecimal required = obligations.stream().map(Event::amount).reduce(ZERO, BigDecimal::add);
        BigDecimal remaining = balance.subtract(required);
        BigDecimal extra = undated == 0 ? remaining.subtract(budget.reserve()).max(ZERO) : null;
        return new PaydayPlan(today, payday, obligations, required, remaining, budget.reserve(), extra, undated, false);
    }

    static List<Event> budgetEvents(YearMonth month, Budget budget) {
        var events = new ArrayList<Event>();
        addEntries(events, month, budget.incomes(), Kind.INCOME);
        addEntries(events, month, budget.expenses(), Kind.EXPENSE);
        budget.oneOffs().stream().filter(entry -> YearMonth.from(entry.date()).equals(month))
                .map(CashCalendar::oneOffEvent).forEach(events::add);
        return events;
    }

    private static boolean completed(Event event, Set<Occurrence> marks) {
        return event.entryId() != null && marks.contains(new Occurrence(event.entryId(), YearMonth.from(event.date())));
    }

    static Event debtEvent(RepaymentPlanner.Payment payment) {
        return new Event(payment.date(), Kind.PAYMENT, payment.name(), payment.amount());
    }

    private static Event oneOffEvent(BudgetController.OneOff entry) {
        Kind kind = entry.direction() == BudgetController.Direction.INCOME ? Kind.INCOME : Kind.EXPENSE;
        return new Event(entry.date(), kind, entry.name(), entry.amount(), entry.id());
    }

    private static void addEntries(List<Event> events, YearMonth month, List<BudgetController.Entry> entries, Kind kind) {
        for (var entry : entries) {
            if (entry.day() != null) events.add(new Event(dayOfMonth(month, entry.day()),
                    kind, entry.name(), entry.amount(), entry.id()));
        }
    }

    private static int undatedCount(List<BudgetController.Entry> entries) {
        return (int) entries.stream().filter(entry -> entry.day() == null).count();
    }

    private static LocalDate dayOfMonth(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
