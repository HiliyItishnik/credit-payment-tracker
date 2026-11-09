package dev.portfolio.credittracker.api;

import com.jayway.jsonpath.JsonPath;
import dev.portfolio.credittracker.api.LoanDtos.RecordPayment;
import dev.portfolio.credittracker.loan.LoanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class LoanApiIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanService service;
    private static final String LOAN = """
            {"name":"Кредит на ремонт","principal":100000.00,"annualRate":12,
             "monthlyPayment":10000.00,"firstPaymentDate":"2026-01-31"}
            """;
    private static final String CARD = """
            {"debtType":"CREDIT_CARD","name":"Карта","principal":50000,"creditLimit":100000,
             "annualRate":36,"monthlyPayment":5000,"firstPaymentDate":"2026-01-31"}
            """;
    private static final String MICROLOAN = """
            {"debtType":"MICROLOAN","name":"Микрозайм","principal":10000,"dailyRate":0.8,
             "interestStartDate":"2026-01-02","monthlyPayment":12400,"firstPaymentDate":"2026-02-01"}
            """;

    private String createDebt(String body) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/loans").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test void defaultsLegacyRequestsToLoanType() throws Exception {
        String id = createLoan();
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.debtType").value("LOAN"));
        assertEquals("LOAN", jdbc.queryForObject("select debt_type from loans", String.class));
    }

    @Test void cardLimitIsPersistedAndAvailableCreditRestoredAfterPayment() throws Exception {
        String id = createDebt(CARD);
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.creditLimit").value(100000))
                .andExpect(jsonPath("$.availableCredit").value(50000)).andExpect(jsonPath("$.debtType").value("CREDIT_CARD"));
        String payment = savedPayment(id, "5000");
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.balance").value(46500))
                .andExpect(jsonPath("$.availableCredit").value(53500));
        mvc.perform(delete("/api/loans/" + id + "/payments/" + payment)).andExpect(status().isNoContent());
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.availableCredit").value(50000));
        mvc.perform(get("/api/loans")).andExpect(jsonPath("$[0].debtType").value("CREDIT_CARD"));
    }

    @Test void microloanUsesDailyRateInForecastPaymentAndUndo() throws Exception {
        String id = createDebt(MICROLOAN);
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.nextInterest").value(2400))
                .andExpect(jsonPath("$.maximumPayment").value(12400)).andExpect(jsonPath("$.dailyRate").value(0.8));
        mvc.perform(get("/api/loans/" + id + "/forecast")).andExpect(jsonPath("$.baseline.totalInterest").value(2400))
                .andExpect(jsonPath("$.baseline.months").value(1));
        String payment = savedPayment(id, "12400");
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.paidOff").value(true));
        mvc.perform(delete("/api/loans/" + id + "/payments/" + payment)).andExpect(status().isNoContent());
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.nextInterest").value(2400));
        assertEquals(0, new BigDecimal("0.8").compareTo(jdbc.queryForObject("select daily_rate from loans", BigDecimal.class)));
    }

    @Test void rejectsMissingOrInconsistentTypeSpecificFields() throws Exception {
        for (String body : new String[]{CARD.replace("100000", "40000"), CARD.replace("\"creditLimit\":100000,", ""),
                MICROLOAN.replace("\"dailyRate\":0.8,", ""), MICROLOAN.replace("2026-01-02", "2026-02-01"),
                LOAN.replace("\"annualRate\":12,", ""), MICROLOAN.replace("\"dailyRate\":0.8,", "\"dailyRate\":0.8,\"annualRate\":12,")}) {
            mvc.perform(post("/api/loans").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent());
        }
        mvc.perform(post("/api/loans").contentType(APPLICATION_JSON).content(MICROLOAN.replace("0.8", "0.12345")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/loans").contentType(APPLICATION_JSON).content(CARD.replace("CREDIT_CARD", "UNKNOWN")))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("select count(*) from loans", Integer.class));
    }

    @BeforeEach void cleanDedicatedTestDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertTrue(database != null && database.endsWith("_test"), "Tests must use a separate database ending in _test");
        jdbc.execute("TRUNCATE TABLE payments, loans, monthly_budget");
    }
    private String createLoan() throws Exception {
        String json = mvc.perform(post("/api/loans").contentType(APPLICATION_JSON).content(LOAN))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }
    private ResultActions pay(String loan, UUID key, String amount) throws Exception {
        return mvc.perform(post("/api/loans/" + loan + "/payments").contentType(APPLICATION_JSON)
                .content("{\"requestId\":\"" + key + "\",\"amount\":" + amount + "}"));
    }
    private String savedPayment(String loan, String amount) throws Exception {
        return JsonPath.read(pay(loan, UUID.randomUUID(), amount).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }
    private int countPayments() { return jdbc.queryForObject("select count(*) from payments", Integer.class); }

    @Test void servesTheBrowserInterface() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/budget.html")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test void persistsMonthlyBudgetAndAppliesExtraToCostliestDebtWithoutRecordingPayments() throws Exception {
        String loan = createLoan();
        savedPayment(loan, "10000");
        createDebt(CARD);
        var body = """
                {"incomes":[{"name":"Зарплата","amount":"40000"}],
                 "expenses":[{"name":"Аренда","amount":17000},{"name":"Транспорт","amount":3000}]}
                """;
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("COMPLETE"))
                .andExpect(jsonPath("$.plan.available").value(20000))
                .andExpect(jsonPath("$.plan.months[0].month").value(YearMonth.now().toString()))
                .andExpect(jsonPath("$.plan.months[0].minimum").value(15000))
                .andExpect(jsonPath("$.plan.months[0].total").value(20000))
                .andExpect(jsonPath("$.plan.months[0].payments[0].name").value("Карта"))
                .andExpect(jsonPath("$.plan.months[0].payments[0].extra").value(5000))
                .andExpect(jsonPath("$.plan.months[0].payments[1].balance").value(81910));
        mvc.perform(get("/api/budget")).andExpect(status().isOk())
                .andExpect(jsonPath("$.budget.expenses[1].name").value("Транспорт"))
                .andExpect(jsonPath("$.plan.available").value(20000));
        assertEquals(1, countPayments());
    }

    @Test void flagsShortfallWhenLaterDebtStartsAndDoesNotSpendFutureIncomeEarly() throws Exception {
        YearMonth now = YearMonth.now();
        createDebt("""
                {"name":"Первый","principal":300,"annualRate":0,"monthlyPayment":300,"firstPaymentDate":"%s"}
                """.formatted(now.atDay(1)));
        createDebt("""
                {"name":"Второй","principal":1000,"annualRate":0,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(now.plusMonths(1).atDay(1)));
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content("""
                {"incomes":[{"name":"Доход","amount":1000}],"expenses":[{"name":"Еда","amount":400}]}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("SHORTFALL"))
                .andExpect(jsonPath("$.plan.months.length()").value(1))
                .andExpect(jsonPath("$.plan.months[0].total").value(300))
                .andExpect(jsonPath("$.plan.months[0].remaining").value(300))
                .andExpect(jsonPath("$.plan.shortfall.month").value(now.plusMonths(1).toString()))
                .andExpect(jsonPath("$.plan.shortfall.missing").value(400));
    }

    @Test void rejectsInvalidBudgetEntriesAndShowsExpensesWithoutDebts() throws Exception {
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content("""
                {"incomes":[{"name":"Доход","amount":-1}],"expenses":[]}
                """)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content("""
                {"incomes":[],"expenses":[{"name":"Еда","amount":1000}]}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("NO_DEBTS"))
                .andExpect(jsonPath("$.plan.available").value(-1000));
    }
    @Test void createsLoanAndCorrectFirstScheduleRow() throws Exception {
        String id = createLoan();
        mvc.perform(get("/api/loans/" + id + "/forecast")).andExpect(status().isOk())
                .andExpect(jsonPath("$.baseline.rows[0].interest").value(1000.0))
                .andExpect(jsonPath("$.baseline.rows[0].principal").value(9000.0))
                .andExpect(jsonPath("$.baseline.rows[0].balance").value(91000.0));
        assertEquals(1, jdbc.queryForObject("select count(*) from loans", Integer.class));
    }
    @Test void persistsPaymentAndUsesItsBalanceForNextMonth() throws Exception {
        String id = createLoan(); savedPayment(id, "10000");
        mvc.perform(get("/api/loans/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(91000.0)).andExpect(jsonPath("$.interestPaid").value(1000.0))
                .andExpect(jsonPath("$.nextPaymentDate").value("2026-02-28"));
        mvc.perform(get("/api/loans/" + id + "/forecast")).andExpect(jsonPath("$.baseline.rows[0].interest").value(910.0));
        assertEquals(1, countPayments());
        assertEquals(0, new BigDecimal("91000").compareTo(jdbc.queryForObject("select balance from payments", BigDecimal.class)));
    }
    @Test void repeatingRequestIdReturnsSamePayment() throws Exception {
        String id = createLoan(); UUID key = UUID.randomUUID();
        String first = JsonPath.read(pay(id, key, "10000").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        pay(id, key, "10000.00").andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(first));
        assertEquals(1, countPayments());
    }
    @Test void sameKeyWithDifferentAmountIsConflict() throws Exception {
        String id = createLoan(); UUID key = UUID.randomUUID();
        pay(id, key, "10000").andExpect(status().isCreated());
        pay(id, key, "11000").andExpect(status().isConflict()); assertEquals(1, countPayments());
    }
    @Test void concurrentRetriesCreateOnlyOnePayment() throws Exception {
        UUID id = UUID.fromString(createLoan());
        var request = new RecordPayment(UUID.randomUUID(), new BigDecimal("10000"));
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { gate.await(); return service.record(id, request); });
            var two = executor.submit(() -> { gate.await(); return service.record(id, request); });
            gate.countDown();
            assertEquals(one.get(15, TimeUnit.SECONDS).id(), two.get(15, TimeUnit.SECONDS).id());
        }
        assertEquals(1, countPayments());
    }
    @Test void rejectsPaymentBelowInterest() throws Exception {
        pay(createLoan(), UUID.randomUUID(), "999.99").andExpect(status().isUnprocessableContent());
        assertEquals(0, countPayments());
    }
    @Test void rejectsOverpayment() throws Exception {
        pay(createLoan(), UUID.randomUUID(), "101000.01").andExpect(status().isUnprocessableContent());
        assertEquals(0, countPayments());
    }
    @Test void supportsFullPayoffAndPreventsFurtherPayments() throws Exception {
        String id = createLoan(); savedPayment(id, "101000");
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.paidOff").value(true)).andExpect(jsonPath("$.balance").value(0.0));
        mvc.perform(get("/api/loans/" + id + "/forecast")).andExpect(jsonPath("$.baseline.months").value(0));
        pay(id, UUID.randomUUID(), "1").andExpect(status().isUnprocessableContent());
        assertEquals(1, countPayments());
    }
    @Test void onlyLastPaymentCanBeUndoneAndBalanceIsRestored() throws Exception {
        String id = createLoan(), first = savedPayment(id, "10000"), last = savedPayment(id, "10000");
        mvc.perform(delete("/api/loans/" + id + "/payments/" + first)).andExpect(status().isConflict());
        mvc.perform(delete("/api/loans/" + id + "/payments/" + last)).andExpect(status().isNoContent());
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.balance").value(91000.0));
        mvc.perform(delete("/api/loans/" + id + "/payments/" + first)).andExpect(status().isNoContent());
        mvc.perform(get("/api/loans/" + id)).andExpect(jsonPath("$.balance").value(100000.0)); assertEquals(0, countPayments());
    }
    @Test void forecastDoesNotWritePayments() throws Exception {
        String id = createLoan();
        mvc.perform(get("/api/loans/" + id + "/forecast").param("extraMonthly", "2000").param("oneTimeExtra", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scenario.rows[0].payment").value(22000.0));
        assertEquals(0, countPayments());
    }
    @Test void validatesRequiredFieldsAndMoneyPrecision() throws Exception {
        mvc.perform(post("/api/loans").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.principal").exists());
        mvc.perform(post("/api/loans").contentType(APPLICATION_JSON).content(LOAN.replace("100000.00", "100000.001")))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("select count(*) from loans", Integer.class));
    }
    @Test void rejectsNonAmortizingLoanAndInvalidForecastInputs() throws Exception {
        mvc.perform(post("/api/loans").contentType(APPLICATION_JSON).content(LOAN.replace("10000.00", "1000.00")))
                .andExpect(status().isUnprocessableContent());
        String id = createLoan();
        mvc.perform(get("/api/loans/" + id + "/forecast").param("extraMonthly", "-1")).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/loans/" + id + "/forecast").param("extraMonthly", "abc")).andExpect(status().isBadRequest());
    }
    @Test void returnsHelpfulErrorsForUnknownLoanAndMalformedRequests() throws Exception {
        mvc.perform(get("/api/loans/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/loans/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/loans").contentType(APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
    @Test void deletingDebtRemovesOnlyItsPaymentHistoryAndCanBeRetried() throws Exception {
        String deleted = createDebt(CARD), kept = createLoan();
        savedPayment(deleted, "5000");
        savedPayment(deleted, "5000");
        savedPayment(kept, "10000");
        mvc.perform(delete("/api/loans/" + deleted)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/loans/" + deleted)).andExpect(status().isNoContent());
        mvc.perform(get("/api/loans/" + deleted)).andExpect(status().isNotFound());
        mvc.perform(get("/api/loans")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(kept));
        mvc.perform(get("/api/loans/" + kept)).andExpect(jsonPath("$.payments.length()").value(1));
        assertEquals(1, countPayments());
    }

    @Test void keepsHistoriesOfDifferentLoansSeparate() throws Exception {
        String first = createLoan(), second = createLoan(); savedPayment(first, "10000");
        mvc.perform(get("/api/loans/" + second)).andExpect(jsonPath("$.payments.length()").value(0))
                .andExpect(jsonPath("$.balance").value(100000.0));
        mvc.perform(get("/api/loans")).andExpect(jsonPath("$.length()").value(2));
    }
}
