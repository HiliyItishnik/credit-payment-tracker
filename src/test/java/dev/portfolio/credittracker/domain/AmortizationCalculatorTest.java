package dev.portfolio.credittracker.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import static dev.portfolio.credittracker.domain.AmortizationCalculator.ZERO;
import static org.junit.jupiter.api.Assertions.*;

class AmortizationCalculatorTest {
    private final AmortizationCalculator calculator = new AmortizationCalculator();
    private BigDecimal number(String value) { return new BigDecimal(value); }
    private AmortizationCalculator.Terms terms(String principal, String rate, String payment) {
        return new AmortizationCalculator.Terms(number(principal), number(rate), number(payment), LocalDate.of(2026, 1, 31));
    }
    private void same(String expected, BigDecimal actual) { assertEquals(0, number(expected).compareTo(actual)); }
    private AmortizationCalculator.Plan plan(AmortizationCalculator.Terms terms) {
        return calculator.plan(terms, terms.principal(), 0, ZERO, ZERO);
    }

    @Test void calculatesInterestPrincipalAndReducedFinalPayment() {
        var result = plan(terms("100000", "12", "10000"));
        var first = result.rows().getFirst();
        same("1000", first.interest()); same("9000", first.principal()); same("91000", first.balance());
        same("0", result.rows().getLast().balance());
        assertTrue(result.rows().getLast().payment().compareTo(number("10000")) < 0);
        same(result.totalPayment().toPlainString(), number("100000").add(result.totalInterest()));
    }
    @Test void supportsZeroInterest() {
        var result = plan(terms("1000", "0", "300"));
        assertEquals(4, result.months()); same("100", result.rows().getLast().payment()); same("0", result.totalInterest());
    }
    @ParameterizedTest @CsvSource({"1,6,0.01", "1,5.99,0.00", "100000,18,1500.00", "100,0,0.00"})
    void roundsInterestOnceWithHalfUp(String balance, String rate, String expected) {
        assertEquals(number(expected), calculator.interest(number(balance), number(rate)));
    }
    @Test void anchorsDatesToOriginalDayAcrossLeapFebruary() {
        var terms = new AmortizationCalculator.Terms(number("1000"), ZERO, number("100"), LocalDate.of(2028, 1, 31));
        var rows = plan(terms).rows();
        assertEquals(LocalDate.of(2028, 2, 29), rows.get(1).date());
        assertEquals(LocalDate.of(2028, 3, 31), rows.get(2).date());
    }
    @ParameterizedTest @ValueSource(strings = {"9.99", "10"})
    void rejectsNonAmortizingMonthlyPayment(String payment) {
        assertThrows(CalculationException.class, () -> plan(terms("1000", "12", payment)));
    }
    @Test void rejectsMoreThanSixHundredFutureMonths() {
        assertThrows(CalculationException.class, () -> plan(terms("10000", "0", "1")));
    }
    @Test void allowsExactlySixHundredMonths() { assertEquals(600, plan(terms("600", "0", "1")).months()); }
    @Test void appliesRecurringExtraEveryMonthAndOneTimeOnlyOnce() {
        var terms = terms("100000", "12", "10000");
        var result = calculator.plan(terms, terms.principal(), 0, number("1000"), number("5000"));
        same("16000", result.rows().getFirst().payment()); same("11000", result.rows().get(1).payment());
        assertTrue(result.months() < plan(terms).months());
        assertTrue(result.totalInterest().compareTo(plan(terms).totalInterest()) < 0);
    }
    @Test void forecastsFromCurrentBalanceAndNextPeriod() {
        var result = calculator.plan(terms("100000", "12", "10000"), number("91000"), 1, ZERO, ZERO);
        assertEquals(2, result.rows().getFirst().number());
        assertEquals(LocalDate.of(2026, 2, 28), result.rows().getFirst().date());
        same("910", result.rows().getFirst().interest());
    }
    @Test void returnsEmptyPlanForRepaidLoan() {
        var result = calculator.plan(terms("1000", "12", "500"), ZERO, 2, ZERO, ZERO);
        assertEquals(0, result.months()); assertTrue(result.rows().isEmpty());
        assertEquals(LocalDate.of(2026, 2, 28), result.payoffDate()); same("0", result.totalPayment());
    }
    @Test void capsExtraPaymentAtDebtPlusInterest() {
        var result = calculator.plan(terms("1000", "12", "100"), number("1000"), 0, number("5000"), number("5000"));
        assertEquals(1, result.months()); same("1010", result.totalPayment()); same("0", result.rows().getFirst().balance());
    }
    @ParameterizedTest @ValueSource(strings = {"-1", "0.001", "1000000000.01"})
    void rejectsInvalidExtras(String extra) {
        var terms = terms("1000", "12", "100");
        assertThrows(CalculationException.class, () -> calculator.plan(terms, terms.principal(), 0, number(extra), ZERO));
        assertThrows(CalculationException.class, () -> calculator.plan(terms, terms.principal(), 0, ZERO, number(extra)));
    }
    @Test void acceptsActualInterestOnlyPaymentWithoutIncreasingDebt() {
        var split = calculator.splitPayment(number("1000"), number("12"), number("10"));
        same("0", split.principal()); same("1000", split.balance());
    }
    @ParameterizedTest @ValueSource(strings = {"-1", "0", "9.99", "1010.01", "100.001"})
    void rejectsInvalidActualPayments(String amount) {
        assertThrows(CalculationException.class, () -> calculator.splitPayment(number("1000"), number("12"), number(amount)));
    }
    @Test void acceptsExactPayoffAndRejectsPaymentsAfterPayoff() {
        var split = calculator.splitPayment(number("1000"), number("12"), number("1010"));
        same("0", split.balance());
        assertThrows(CalculationException.class, () -> calculator.splitPayment(split.balance(), number("12"), number("10")));
    }
    @ParameterizedTest @CsvSource({"0.01,0,0.01", "12.34,3.75,2", "300000,18,15000", "100000000,100,10000000"})
    void conservesMoneyAcrossSmallAndLargeLoans(String principal, String rate, String payment) {
        var result = plan(terms(principal, rate, payment));
        BigDecimal principalSum = ZERO;
        BigDecimal previous = number(principal);
        for (var row : result.rows()) {
            assertEquals(0, row.payment().compareTo(row.interest().add(row.principal())));
            assertEquals(0, previous.subtract(row.principal()).compareTo(row.balance()));
            assertTrue(row.balance().signum() >= 0);
            assertEquals(2, row.interest().scale());
            principalSum = principalSum.add(row.principal()); previous = row.balance();
        }
        same(principal, principalSum); same("0", previous);
        assertEquals(0, number(principal).add(result.totalInterest()).compareTo(result.totalPayment()));
    }
}
