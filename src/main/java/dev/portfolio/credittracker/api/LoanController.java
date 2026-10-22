package dev.portfolio.credittracker.api;

import dev.portfolio.credittracker.api.LoanDtos.*;
import dev.portfolio.credittracker.loan.LoanService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans")
public class LoanController {
    private final LoanService service;
    public LoanController(LoanService service) { this.service = service; }

    @GetMapping
    public List<LoanSummary> list() { return service.list(); }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<LoanDetail> create(@Valid @RequestBody CreateLoan request) {
        LoanDetail loan = service.create(request);
        return ResponseEntity.created(URI.create("/api/loans/" + loan.id())).body(loan);
    }

    @GetMapping("/{id}")
    public LoanDetail get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/{id}/forecast")
    public Forecast forecast(@PathVariable UUID id,
                             @RequestParam(defaultValue = "0") BigDecimal extraMonthly,
                             @RequestParam(defaultValue = "0") BigDecimal oneTimeExtra) {
        return service.forecast(id, extraMonthly, oneTimeExtra);
    }

    @PostMapping(value = "/{id}/payments", consumes = "application/json")
    public ResponseEntity<PaymentView> record(@PathVariable UUID id, @Valid @RequestBody RecordPayment request) {
        PaymentView payment = service.record(id, request);
        return ResponseEntity.status(201).body(payment);
    }

    @DeleteMapping("/{id}/payments/{paymentId}")
    public ResponseEntity<Void> undo(@PathVariable UUID id, @PathVariable UUID paymentId) {
        service.undo(id, paymentId);
        return ResponseEntity.noContent().build();
    }
}
