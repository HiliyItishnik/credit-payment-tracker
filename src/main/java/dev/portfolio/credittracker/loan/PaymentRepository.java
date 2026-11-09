package dev.portfolio.credittracker.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    List<Payment> findByLoanIdOrderByInstallmentNumberAsc(UUID loanId);
    Optional<Payment> findByLoanIdAndRequestId(UUID loanId, UUID requestId);
    Optional<Payment> findFirstByLoanIdOrderByInstallmentNumberDesc(UUID loanId);

    @Modifying
    @Query("delete from Payment payment where payment.loan.id = :loanId")
    void deleteAllForLoan(@Param("loanId") UUID loanId);
}
