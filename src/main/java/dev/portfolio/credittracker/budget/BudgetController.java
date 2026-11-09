package dev.portfolio.credittracker.budget;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/budget")
public class BudgetController {
    public record Entry(@NotBlank @Size(max = 80) String name,
                        @NotNull @DecimalMin("0.01") @DecimalMax("1000000000")
                        @Digits(integer = 10, fraction = 2) BigDecimal amount) {}

    public record Budget(@NotNull @Size(max = 50) List<@NotNull @Valid Entry> incomes,
                         @NotNull @Size(max = 50) List<@NotNull @Valid Entry> expenses) {}

    public record Overview(Budget budget, RepaymentPlanner.Plan plan) {}

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
}
