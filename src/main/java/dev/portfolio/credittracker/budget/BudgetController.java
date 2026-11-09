package dev.portfolio.credittracker.budget;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/budget")
public class BudgetController {
    public record Entry(@NotBlank @Size(max = 80) String name,
                        @NotNull @DecimalMin("0.01") @DecimalMax("1000000000")
                        @Digits(integer = 10, fraction = 2) BigDecimal amount,
                        @Min(1) @Max(31) Integer day, UUID id) {
        public Entry { if (id == null) id = UUID.randomUUID(); }
    }

    public enum Direction { INCOME, EXPENSE }
    public record OneOff(@NotBlank @Size(max = 80) String name,
                         @NotNull @DecimalMin("0.01") @DecimalMax("1000000000")
                         @Digits(integer = 10, fraction = 2) BigDecimal amount,
                         @NotNull LocalDate date, @NotNull Direction direction, UUID id) {
        public OneOff { if (id == null) id = UUID.randomUUID(); }
    }

    public record Budget(@NotNull @Size(max = 50) List<@NotNull @Valid Entry> incomes,
                         @NotNull @Size(max = 50) List<@NotNull @Valid Entry> expenses,
                         @Size(max = 100) List<@NotNull @Valid OneOff> oneOffs,
                         @DecimalMin("0") @DecimalMax("50000000000") @Digits(integer = 11, fraction = 2) BigDecimal reserve,
                         @DecimalMin("0") @DecimalMax("50000000000") @Digits(integer = 11, fraction = 2) BigDecimal openingReserve) {
        public Budget {
            if (oneOffs == null) oneOffs = List.of();
            if (reserve == null) reserve = BigDecimal.ZERO;
            if (openingReserve == null) openingReserve = BigDecimal.ZERO;
        }
    }

    public record Overview(Budget budget, RepaymentPlanner.Plan plan, RepaymentPlanner.Plan alternative) {}
    public record Actual(@NotNull @DecimalMin("0") @DecimalMax("50000000000")
                         @Digits(integer = 11, fraction = 2) BigDecimal income,
                         @NotNull @DecimalMin("0") @DecimalMax("50000000000")
                         @Digits(integer = 11, fraction = 2) BigDecimal expenses) {}
    public record Totals(BigDecimal income, BigDecimal expenses, BigDecimal payments) {}
    public record Forecast(RepaymentPlanner.Status status, YearMonth payoffMonth, RepaymentPlanner.Shortfall shortfall) {
        Forecast(RepaymentPlanner.Plan plan) { this(plan.status(), plan.payoffMonth(), plan.shortfall()); }
    }
    public record MonthReview(YearMonth month, Totals planned, Actual actual, BigDecimal paid,
                              Forecast baseline, Forecast adjusted) {}
    public record PrincipalPaid(YearMonth month, BigDecimal amount) {}
    public record DebtOverview(YearMonth month, BigDecimal balance, BigDecimal due, List<PrincipalPaid> principalPaid) {}
    public record CalendarMark(@NotNull Boolean completed) {}

    private final BudgetService service;

    public BudgetController(BudgetService service) {
        this.service = service;
    }

    @GetMapping
    public Overview get() {
        return service.get();
    }

    @PutMapping
    public Overview save(@Valid @RequestBody Budget budget) {
        return service.save(budget);
    }

    @GetMapping("/calendar/{month}")
    public CashCalendar.Result calendar(@PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                                        @RequestParam(defaultValue = "0") BigDecimal openingBalance) {
        return service.calendar(month, openingBalance);
    }

    @PutMapping("/calendar/{month}/entries/{id}")
    public ResponseEntity<Void> markCalendarEntry(
            @PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
            @PathVariable UUID id, @Valid @RequestBody CalendarMark mark) {
        service.markCalendarEntry(month, id, mark.completed());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/until-payday")
    public CashCalendar.PaydayPlan untilPayday(@RequestParam BigDecimal balance) {
        return service.untilPayday(balance);
    }

    @GetMapping("/debt-overview")
    public DebtOverview debtOverview() {
        return service.debtOverview();
    }

    @GetMapping(value = "/reminders.ics", produces = "text/calendar;charset=UTF-8")
    public ResponseEntity<String> reminders(@RequestParam(defaultValue = "3") @Min(1) @Max(30) int days) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payment-reminders.ics\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.reminders(days));
    }

    @GetMapping("/months/{month}")
    public MonthReview month(@PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month) {
        return service.review(month);
    }

    @PutMapping("/months/{month}")
    public MonthReview saveMonth(@PathVariable @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
                                 @Valid @RequestBody Actual actual) {
        return service.saveActual(month, actual);
    }
}
