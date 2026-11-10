package dev.portfolio.credittracker.api;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.hamcrest.Matchers.contains;
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
    @Autowired EntityManagerFactory entityManagerFactory;
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
        jdbc.execute("TRUNCATE TABLE payments, loans, monthly_budget, budget_actuals, budget_calendar_marks");
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
        jdbc.update("UPDATE payments SET recorded_at = CAST(? AS date)", YearMonth.now().minusMonths(1).atDay(15));
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

    @Test void comparesStrategiesUsingTheSameBudgetAndDifferentExtraPaymentTargets() throws Exception {
        var due = YearMonth.now().atDay(1);
        createDebt("""
                {"name":"Дорогой","principal":5000,"annualRate":36,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(due));
        createDebt("""
                {"name":"Маленький","principal":2000,"annualRate":0,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(due));
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content("""
                {"incomes":[{"name":"Доход","amount":3000}],"expenses":[]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.months[0].payments[0].name").value("Дорогой"))
                .andExpect(jsonPath("$.alternative.months[0].payments[0].name").value("Маленький"))
                .andExpect(jsonPath("$.alternative.months[0].payments[0].balance").value(0))
                .andExpect(jsonPath("$.plan.interest").value(281.84))
                .andExpect(jsonPath("$.alternative.interest").value(312.74));
    }

    @Test void calendarFindsCashGapBeforePaydayDespiteMonthlySurplus() throws Exception {
        YearMonth month = YearMonth.now().plusMonths(1);
        createDebt("""
                {"name":"Кредит","principal":1000,"annualRate":0,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(month.atDay(10)));
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content("""
                {"incomes":[{"name":"Зарплата","amount":5000,"day":20}],
                 "expenses":[{"name":"Аренда","amount":2000,"day":5},{"name":"Коммунальные","amount":100,"day":31}]}
                """)).andExpect(status().isOk());
        mvc.perform(get("/api/budget/calendar/" + month).param("openingBalance", "2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstGap.date").value(month.atDay(10).toString()))
                .andExpect(jsonPath("$.firstGap.missing").value(1000))
                .andExpect(jsonPath("$.rows[3].event.date").value(month.atEndOfMonth().toString()))
                .andExpect(jsonPath("$.rows[3].balance").value(3900));
    }

    @Test void paydayPlanUsesCurrentBalanceAndOnlyExpensesBeforeNextDatedIncome() throws Exception {
        LocalDate today = LocalDate.now();
        createDebt("""
                {"name":"Кредит","principal":5000,"annualRate":0,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(today));
        String budget = """
                {"incomes":[],"expenses":[],"reserve":1500,
                 "oneOffs":[{"name":"Расход сегодня","amount":500,"date":"%s","direction":"EXPENSE"},
                            {"name":"Доход","amount":5000,"date":"%s","direction":"INCOME"},
                            {"name":"Расход в день дохода","amount":2000,"date":"%s","direction":"EXPENSE"}]}
                """.formatted(today, today.plusDays(2), today.plusDays(2));
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content(budget)).andExpect(status().isOk());
        mvc.perform(get("/api/budget/until-payday").param("balance", "4000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextIncomeDate").value(today.plusDays(2).toString()))
                .andExpect(jsonPath("$.obligations.length()").value(2))
                .andExpect(jsonPath("$.required").value(1500))
                .andExpect(jsonPath("$.remaining").value(2500))
                .andExpect(jsonPath("$.extra").value(1000));
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON)
                .content(budget.replace("\"expenses\":[]", "\"expenses\":[{\"name\":\"Еда\",\"amount\":1000}]")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/budget/until-payday").param("balance", "4000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.undatedExpenses").value(1))
                .andExpect(jsonPath("$.extra").doesNotExist());
    }

    @Test void calendarMarksSkipPaidExpensesAndEarlyIncomeWithoutMarkingLaterOccurrences() throws Exception {
        LocalDate today = LocalDate.now();
        YearMonth month = YearMonth.from(today);
        UUID expense = UUID.randomUUID();
        UUID income = UUID.randomUUID();
        UUID recurring = UUID.randomUUID();
        String budget = """
                {"incomes":[{"id":"%s","name":"Зарплата","amount":6000,"day":1}],
                 "expenses":[{"id":"%s","name":"Аренда","amount":1000,"day":%s}],
                 "oneOffs":[{"id":"%s","name":"Премия","amount":5000,"date":"%s","direction":"INCOME"}]}
                """.formatted(recurring, expense, today.getDayOfMonth(), income, today.plusDays(1));
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content(budget)).andExpect(status().isOk());
        String payday = "/api/budget/until-payday";
        mvc.perform(get(payday).param("balance", "4000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextIncomeDate").value(today.plusDays(1).toString()))
                .andExpect(jsonPath("$.required").value(1000));
        String expenseMark = "/api/budget/calendar/" + month + "/entries/" + expense;
        String incomeMark = "/api/budget/calendar/" + YearMonth.from(today.plusDays(1)) + "/entries/" + income;
        for (String path : List.of(expenseMark, incomeMark)) {
            mvc.perform(put(path).contentType(APPLICATION_JSON).content("{\"completed\":true}"))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/budget/calendar/" + month)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.event.entryId == '%s')].completed".formatted(expense), contains(true)));
        mvc.perform(get(payday).param("balance", "4000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextIncomeDate").value(month.plusMonths(1).atDay(1).toString()))
                .andExpect(jsonPath("$.required").value(0));
        mvc.perform(get("/api/budget/calendar/" + month.plusMonths(1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.event.entryId == '%s')].completed".formatted(expense), contains(false)));
        mvc.perform(put(expenseMark).contentType(APPLICATION_JSON).content("{\"completed\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(payday).param("balance", "4000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(1000));
    }

    @Test void debtOverviewSumsBalancesAndGroupsOnlyPrincipalByActualPaymentMonth() throws Exception {
        YearMonth current = YearMonth.now();
        String id = createDebt("""
                {"name":"Кредит","principal":5000,"annualRate":12,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(current.minusMonths(1).atDay(1)));
        mvc.perform(post("/api/loans/" + id + "/payments").contentType(APPLICATION_JSON).content("""
                {"requestId":"%s","amount":1000,"paidOn":"%s"}
                """.formatted(UUID.randomUUID(), current.minusMonths(1).atEndOfMonth())))
                .andExpect(status().isCreated());
        createDebt("""
                {"name":"Другой кредит","principal":3000,"annualRate":0,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(current.atDay(1)));
        mvc.perform(get("/api/budget/debt-overview")).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(7050))
                .andExpect(jsonPath("$.due").value(2000))
                .andExpect(jsonPath("$.principalPaid.length()").value(3))
                .andExpect(jsonPath("$.principalPaid[0].amount").value(0))
                .andExpect(jsonPath("$.principalPaid[1].month").value(current.minusMonths(1).toString()))
                .andExpect(jsonPath("$.principalPaid[1].amount").value(950))
                .andExpect(jsonPath("$.principalPaid[2].amount").value(0));
    }

    @Test void actualsKeepOriginalPlanAndReforecastWithoutDoubleCountingRecordedPayments() throws Exception {
        YearMonth month = YearMonth.now();
        var date = month.atDay(1);
        String paidOff = createDebt("""
                {"name":"Закрытый","principal":1000,"annualRate":0,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(date));
        savedPayment(paidOff, "1000");
        createDebt("""
                {"name":"Остаток","principal":9000,"annualRate":0,"monthlyPayment":2000,"firstPaymentDate":"%s"}
                """.formatted(date));
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content("""
                {"incomes":[{"name":"Зарплата","amount":5000}],"expenses":[]}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.plan.months[0].total").value(4000));
        String endpoint = "/api/budget/months/" + month;
        mvc.perform(put(endpoint).contentType(APPLICATION_JSON).content("""
                {"income":5000,"expenses":2000}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(1000))
                .andExpect(jsonPath("$.planned.payments").value(5000))
                .andExpect(jsonPath("$.baseline.payoffMonth").value(month.plusMonths(1).toString()))
                .andExpect(jsonPath("$.adjusted.payoffMonth").value(month.plusMonths(2).toString()));
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content("""
                {"incomes":[{"name":"Зарплата","amount":7000}],"expenses":[]}
                """)).andExpect(status().isOk());
        mvc.perform(get(endpoint)).andExpect(status().isOk())
                .andExpect(jsonPath("$.planned.income").value(5000))
                .andExpect(jsonPath("$.actual.expenses").value(2000));
        mvc.perform(put(endpoint).contentType(APPLICATION_JSON).content("""
                {"income":5000,"expenses":2500}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.planned.income").value(5000))
                .andExpect(jsonPath("$.adjusted.shortfall.missing").value(500));
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

    @Test void budgetReportsPaymentInActualMonthAndKeepsScheduleDate() throws Exception {
        YearMonth now = YearMonth.now();
        String id = createDebt("""
                {"name":"Кредит","principal":5000,"annualRate":0,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(now.atDay(1)));
        String paidOn = now.minusMonths(1).atEndOfMonth().toString();
        UUID key = UUID.randomUUID();
        String body = """
                {"requestId":"%s","amount":1000,"paidOn":"%s"}
                """.formatted(key, paidOn);
        mvc.perform(post("/api/loans/" + id + "/payments").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.paidOn").value(paidOn))
                .andExpect(jsonPath("$.date").value(now.atDay(1).toString()));
        mvc.perform(post("/api/loans/" + id + "/payments").contentType(APPLICATION_JSON)
                .content(body.replace(paidOn, now.atDay(1).toString()))).andExpect(status().isConflict());
        mvc.perform(get("/api/budget/months/" + now.minusMonths(1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(1000));
        mvc.perform(get("/api/budget/months/" + now)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(0));
    }

    @Test void oneOffOperationsAffectOnlyTheirMonthAndReserveFundsMinimumsBeforeExtras() throws Exception {
        YearMonth now = YearMonth.now();
        createDebt("""
                {"name":"Кредит","principal":7000,"annualRate":0,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(now.atDay(1)));
        mvc.perform(put("/api/budget").contentType(APPLICATION_JSON).content("""
                {"incomes":[{"name":"Зарплата","amount":1000,"day":1}],"expenses":[],
                 "reserve":1000,"openingReserve":1000,
                 "oneOffs":[{"name":"Ремонт","amount":500,"date":"%s","direction":"EXPENSE"},
                            {"name":"Премия","amount":2000,"date":"%s","direction":"INCOME"}]}
                """.formatted(now.plusMonths(1).atDay(2), now.plusMonths(2).atDay(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.months[0].reserveBalance").value(1000))
                .andExpect(jsonPath("$.plan.months[1].reserveUsed").value(500))
                .andExpect(jsonPath("$.plan.months[1].payments[0].extra").value(0))
                .andExpect(jsonPath("$.plan.months[2].reserveBalance").value(1000))
                .andExpect(jsonPath("$.plan.months[2].payments[0].extra").value(1500))
                .andExpect(jsonPath("$.plan.months[3].payments[0].extra").value(0));
        mvc.perform(get("/api/budget/calendar/" + now.plusMonths(1)).param("openingBalance", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[2].event.name").value("Ремонт"))
                .andExpect(jsonPath("$.reserveGap.missing").value(500));
    }

    @Test void exportsImportableRemindersWithStableIdsAndEscapedNames() throws Exception {
        YearMonth month = YearMonth.now().plusMonths(1);
        String id = createDebt("""
                {"name":"Кредит, пример; платёж","principal":1000,"annualRate":0,"monthlyPayment":1000,"firstPaymentDate":"%s"}
                """.formatted(month.atDay(15)));
        String calendar = mvc.perform(get("/api/budget/reminders.ics").param("days", "5"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"payment-reminders.ics\""))
                .andReturn().getResponse().getContentAsString();
        assertTrue(calendar.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"));
        assertTrue(calendar.contains("UID:" + id + "-1@credit-payment-tracker\r\n"));
        assertTrue(calendar.contains("DTSTART:" + month.atDay(15).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "T090000\r\n"));
        assertTrue(calendar.replace("\r\n ", "").contains("Кредит\\, пример\\; платёж"));
        assertTrue(calendar.contains("TRIGGER:-P5D\r\n"));
        mvc.perform(get("/api/budget/reminders.ics").param("days", "0")).andExpect(status().isBadRequest());
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

    @Test void loadingManyLoansUsesABoundedNumberOfQueries() throws Exception {
        for (int index = 0; index < 6; index++) {
            String id = createLoan();
            if (index % 2 == 0) {
                savedPayment(id, "10000");
                savedPayment(id, "10000");
            }
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            mvc.perform(get("/api/loans")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.balance == 81910)]", org.hamcrest.Matchers.hasSize(3)))
                    .andExpect(jsonPath("$[?(@.balance == 100000)]", org.hamcrest.Matchers.hasSize(3)));
            assertTrue(statistics.getPrepareStatementCount() <= 2, "Loan list must avoid per-loan queries");
            statistics.clear();
            mvc.perform(get("/api/budget/debt-overview")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(545730));
            assertTrue(statistics.getPrepareStatementCount() <= 2, "Budget must avoid per-loan queries");
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test void keepsHistoriesOfDifferentLoansSeparate() throws Exception {
        String first = createLoan(), second = createLoan(); savedPayment(first, "10000");
        mvc.perform(get("/api/loans/" + second)).andExpect(jsonPath("$.payments.length()").value(0))
                .andExpect(jsonPath("$.balance").value(100000.0));
        mvc.perform(get("/api/loans")).andExpect(jsonPath("$.length()").value(2));
    }
}
