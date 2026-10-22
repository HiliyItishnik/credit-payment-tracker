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

    @BeforeEach void cleanDedicatedTestDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertTrue(database != null && database.endsWith("_test"), "Tests must use a separate database ending in _test");
        jdbc.execute("TRUNCATE TABLE payments, loans");
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
    @Test void keepsHistoriesOfDifferentLoansSeparate() throws Exception {
        String first = createLoan(), second = createLoan(); savedPayment(first, "10000");
        mvc.perform(get("/api/loans/" + second)).andExpect(jsonPath("$.payments.length()").value(0))
                .andExpect(jsonPath("$.balance").value(100000.0));
        mvc.perform(get("/api/loans")).andExpect(jsonPath("$.length()").value(2));
    }
}
