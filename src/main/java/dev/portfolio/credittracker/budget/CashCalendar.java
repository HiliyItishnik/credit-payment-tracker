package dev.portfolio.credittracker.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.portfolio.credittracker.budget.BudgetController.Budget;
import static dev.portfolio.credittracker.domain.AmortizationCalculator.ZERO;

final class CashCalendar {
    enum Kind { INCOME, EXPENSE, PAYMENT, RECORDED_PAYMENT }
    record Event(LocalDate date, Kind kind, String name, BigDecimal amount) {}
    record Row(Event event, BigDecimal balance) {}
    record Gap(LocalDate date, BigDecimal missing) {}
    record Result(YearMonth month, BigDecimal openingBalance, List<Row> rows, Gap firstGap,
                  int undatedEntries, boolean incompleteDebtSchedule) {}

    static Result build(YearMonth month, BigDecimal opening, Budget budget, List<Event> debtEvents,
                        boolean incompleteDebtSchedule) {
        var events = new ArrayList<>(debtEvents);
        int undated = addEntries(events, month, budget.incomes(), Kind.INCOME)
                + addEntries(events, month, budget.expenses(), Kind.EXPENSE);
        events.sort(Comparator.comparing(Event::date).thenComparing(Event::kind));
        BigDecimal balance = opening;
        Gap gap = null;
        var rows = new ArrayList<Row>();
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            balance = event.kind() == Kind.INCOME ? balance.add(event.amount()) : balance.subtract(event.amount());
            rows.add(new Row(event, balance));
            boolean endOfDay = i + 1 == events.size() || !events.get(i + 1).date().equals(event.date());
            if (endOfDay && gap == null && balance.compareTo(ZERO) < 0) gap = new Gap(event.date(), balance.negate());
        }
        return new Result(month, opening, List.copyOf(rows), gap, undated, incompleteDebtSchedule);
    }

    private static int addEntries(List<Event> events, YearMonth month, List<BudgetController.Entry> entries, Kind kind) {
        int undated = 0;
        for (var entry : entries) {
            if (entry.day() == null) undated++;
            else events.add(new Event(month.atDay(Math.min(entry.day(), month.lengthOfMonth())),
                    kind, entry.name(), entry.amount()));
        }
        return undated;
    }
}
