ALTER TABLE loans
    ADD COLUMN debt_type VARCHAR(20) NOT NULL DEFAULT 'LOAN',
    ADD COLUMN credit_limit NUMERIC(16, 2),
    ADD COLUMN daily_rate NUMERIC(7, 4),
    ADD COLUMN interest_start_date DATE;

ALTER TABLE loans ADD CONSTRAINT loan_debt_type_valid CHECK (
    (debt_type = 'LOAN' AND credit_limit IS NULL AND daily_rate IS NULL AND interest_start_date IS NULL)
    OR (debt_type = 'CREDIT_CARD' AND credit_limit >= principal AND daily_rate IS NULL AND interest_start_date IS NULL)
    OR (debt_type = 'MICROLOAN' AND credit_limit IS NULL AND annual_rate = 0
        AND daily_rate BETWEEN 0 AND 100 AND interest_start_date < first_payment_date)
);
ALTER TABLE loans ADD CONSTRAINT loan_type_fields_required CHECK (
    (debt_type <> 'CREDIT_CARD' OR credit_limit IS NOT NULL)
    AND (debt_type <> 'MICROLOAN' OR (daily_rate IS NOT NULL AND interest_start_date IS NOT NULL))
);
