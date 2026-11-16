package dev.portfolio.credittracker.backup;

import dev.portfolio.credittracker.api.LoanDtos.CreateLoan;
import dev.portfolio.credittracker.api.LoanDtos.RecordPayment;
import dev.portfolio.credittracker.budget.BudgetController;
import dev.portfolio.credittracker.budget.BudgetService;
import dev.portfolio.credittracker.domain.AmortizationCalculator;
import dev.portfolio.credittracker.domain.CalculationException;
import dev.portfolio.credittracker.loan.DebtType;
import dev.portfolio.credittracker.loan.LoanService;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class BackupDataValidator {
    private final Validator validator;
    private final ObjectMapper json;
    private final LoanService loans;
    private final BudgetService budgets;
    private final AmortizationCalculator calculator;

    public BackupDataValidator(Validator validator, ObjectMapper json, LoanService loans,
                               BudgetService budgets, AmortizationCalculator calculator) {
        this.validator = validator;
        this.json = json;
        this.loans = loans;
        this.budgets = budgets;
        this.calculator = calculator;
    }

    public void validate(Map<String, List<Map<String, Object>>> data) {
        try {
            validateData(data);
        } catch (CalculationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new CalculationException("В резервной копии некорректные суммы, даты или история платежей.");
        }
    }

    private void validateData(Map<String, List<Map<String, Object>>> data) {
        var terms = new HashMap<UUID, AmortizationCalculator.Terms>();
        for (var row : data.get("loans")) {
            UUID id = uuid(row, "id");
            var type = DebtType.valueOf(text(row, "debt_type"));
            var request = new CreateLoan(text(row, "name"), amount(row, "principal"), amount(row, "annual_rate"),
                    amount(row, "monthly_payment"), date(row, "first_payment_date"), type,
                    nullableAmount(row, "credit_limit"), nullableAmount(row, "daily_rate"), nullableDate(row, "interest_start_date"));
            require(validator.validate(request).isEmpty());
            loans.validateCreate(request);
            Instant.parse(text(row, "created_at"));
            require(terms.put(id, new AmortizationCalculator.Terms(request.principal(), request.annualRate(),
                    request.monthlyPayment(), request.firstPaymentDate(), request.dailyRate(), request.interestStartDate())) == null);
        }
        var payments = new HashMap<UUID, List<Map<String, Object>>>();
        var paymentIds = new HashSet<UUID>();
        for (var row : data.get("payments")) {
            require(paymentIds.add(uuid(row, "id")));
            UUID loan = uuid(row, "loan_id");
            require(terms.containsKey(loan));
            payments.computeIfAbsent(loan, key -> new java.util.ArrayList<>()).add(row);
        }
        payments.forEach((id, history) -> validateHistory(terms.get(id), history));
        require(data.get("monthly_budget").size() <= 1);
        for (var row : data.get("monthly_budget")) {
            require(amount(row, "id").intValueExact() == 1 && amount(row, "version").longValueExact() >= 0);
            var budget = json.convertValue(row.get("entries"), BudgetController.Budget.class);
            require(validator.validate(budget).isEmpty());
            budgets.validateBudget(budget);
            // IDs must be present in the file, rather than generated during deserialization.
            var entries = (Map<?, ?>) row.get("entries");
            for (String group : List.of("incomes", "expenses", "oneOffs")) {
                if (entries.get(group) instanceof List<?> values) {
                    for (Object entry : values) UUID.fromString(((Map<?, ?>) entry).get("id").toString());
                }
            }
        }
        for (String table : List.of("budget_actuals", "budget_calendar_marks", "budget_calendar_dates", "budget_calendar_amounts")) {
            var keys = new HashSet<String>();
            for (var row : data.get(table)) {
                LocalDate month = date(row, "month");
                require(month.getDayOfMonth() == 1 && month.getYear() >= 1900 && month.getYear() <= 2200);
                String key = month.toString();
                if (!table.equals("budget_actuals")) key += uuid(row, "entry_id");
                require(keys.add(key));
                if (table.equals("budget_actuals")) {
                    var actual = json.convertValue(row.get("actual"), BudgetController.Actual.class);
                    require(validator.validate(actual).isEmpty());
                    var planned = json.convertValue(row.get("planned"), BudgetController.Totals.class);
                    require(planned.income().signum() >= 0 && planned.expenses().signum() >= 0
                            && (planned.payments() == null || planned.payments().signum() >= 0));
                }
                if (table.equals("budget_calendar_dates")) {
                    LocalDate scheduled = date(row, "scheduled_date");
                    require(scheduled.getYear() >= 1900 && scheduled.getYear() <= 2200);
                }
                if (table.equals("budget_calendar_amounts")) {
                    BigDecimal value = amount(row, "amount");
                    require(value.signum() > 0 && value.scale() <= 2 && value.compareTo(AmortizationCalculator.MAX_AMOUNT) <= 0);
                }
            }
        }
    }

    private void validateHistory(AmortizationCalculator.Terms terms, List<Map<String, Object>> history) {
        history.sort(Comparator.comparing(row -> amount(row, "installment_number")));
        BigDecimal balance = terms.principal();
        var requests = new HashSet<UUID>();
        for (int index = 0; index < history.size(); index++) {
            var row = history.get(index);
            UUID requestId = uuid(row, "request_id");
            require(requests.add(requestId));
            var request = new RecordPayment(requestId, amount(row, "amount"), nullableDate(row, "paid_on"));
            require(validator.validate(request).isEmpty());
            require(amount(row, "installment_number").intValueExact() == index + 1);
            require(date(row, "due_date").equals(terms.firstPaymentDate().plusMonths(index)));
            Instant.parse(text(row, "recorded_at"));
            var split = calculator.splitPayment(terms, balance, index, request.amount());
            require(split.interest().compareTo(amount(row, "interest")) == 0
                    && split.principal().compareTo(amount(row, "principal")) == 0
                    && split.balance().compareTo(amount(row, "balance")) == 0);
            balance = split.balance();
        }
    }

    private void require(boolean valid) {
        if (!valid) throw new CalculationException("Резервная копия содержит несогласованные данные или историю платежей.");
    }
    private String text(Map<String, Object> row, String field) { return row.get(field).toString(); }
    private UUID uuid(Map<String, Object> row, String field) { return UUID.fromString(text(row, field)); }
    private LocalDate date(Map<String, Object> row, String field) { return LocalDate.parse(text(row, field)); }
    private LocalDate nullableDate(Map<String, Object> row, String field) { return row.get(field) == null ? null : date(row, field); }
    private BigDecimal amount(Map<String, Object> row, String field) { return new BigDecimal(text(row, field)); }
    private BigDecimal nullableAmount(Map<String, Object> row, String field) { return row.get(field) == null ? null : amount(row, field); }
}
