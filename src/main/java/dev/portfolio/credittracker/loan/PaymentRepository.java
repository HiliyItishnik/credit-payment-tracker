package dev.portfolio.credittracker.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    interface LatestPayment {
        UUID getLoanId();
        BigDecimal getBalance();
        int getInstallmentNumber();
    }

    @Query("""
            select payment.loan.id as loanId, payment.balance as balance,
                   payment.installmentNumber as installmentNumber
            from Payment payment
            where not exists (
                select newer.id from Payment newer
                where newer.loan.id = payment.loan.id
                  and newer.installmentNumber > payment.installmentNumber
            )
            """)
    List<LatestPayment> findLatestForAllLoans();

    List<Payment> findByLoanIdOrderByInstallmentNumberAsc(UUID loanId);
    Optional<Payment> findByLoanIdAndRequestId(UUID loanId, UUID requestId);
    Optional<Payment> findFirstByLoanIdOrderByInstallmentNumberDesc(UUID loanId);

    @Modifying
    @Query("delete from Payment payment where payment.loan.id = :loanId")
    void deleteAllForLoan(@Param("loanId") UUID loanId);
}
