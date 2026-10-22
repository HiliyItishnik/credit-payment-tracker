package dev.portfolio.credittracker.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    List<Payment> findByLoanIdOrderByInstallmentNumberAsc(UUID loanId);
    Optional<Payment> findByLoanIdAndRequestId(UUID loanId, UUID requestId);
    Optional<Payment> findFirstByLoanIdOrderByInstallmentNumberDesc(UUID loanId);
}
