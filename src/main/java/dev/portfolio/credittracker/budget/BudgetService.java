package dev.portfolio.credittracker.budget;

import dev.portfolio.credittracker.loan.LoanRepository;
import dev.portfolio.credittracker.loan.PaymentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static dev.portfolio.credittracker.budget.BudgetController.*;
import static dev.portfolio.credittracker.domain.AmortizationCalculator.ZERO;

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
        Budget budget = jdbc.sql("SELECT entries::text FROM monthly_budget WHERE id = 1")
                .query(String.class).optional().map(value -> json.readValue(value, Budget.class))
                .orElseGet(() -> new Budget(List.of(), List.of()));
        return overview(budget);
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
        var debts = loans.findAllByOrderByCreatedAtAsc().stream().map(loan -> {
            var last = payments.findFirstByLoanIdOrderByInstallmentNumberDesc(loan.getId());
            return new RepaymentPlanner.Debt(loan.getName(), loan.terms(),
                    last.map(payment -> payment.getBalance()).orElse(loan.getPrincipal()),
                    last.map(payment -> payment.getInstallmentNumber()).orElse(0));
        }).toList();
        BigDecimal income = total(budget.incomes());
        BigDecimal expenses = total(budget.expenses());
        return new Overview(budget, planner.plan(debts, YearMonth.now(), income, expenses));
    }

    private BigDecimal total(List<Entry> entries) {
        return entries.stream().map(Entry::amount).reduce(ZERO, BigDecimal::add);
    }
}
