CREATE TABLE loans (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    principal NUMERIC(16, 2) NOT NULL CHECK (principal > 0),
    annual_rate NUMERIC(7, 4) NOT NULL CHECK (annual_rate BETWEEN 0 AND 100),
    monthly_payment NUMERIC(16, 2) NOT NULL CHECK (monthly_payment > 0),
    first_payment_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    loan_id UUID NOT NULL REFERENCES loans(id),
    request_id UUID NOT NULL,
    installment_number INTEGER NOT NULL CHECK (installment_number > 0),
    due_date DATE NOT NULL,
    amount NUMERIC(16, 2) NOT NULL CHECK (amount > 0),
    interest NUMERIC(16, 2) NOT NULL CHECK (interest >= 0),
    principal NUMERIC(16, 2) NOT NULL CHECK (principal >= 0),
    balance NUMERIC(16, 2) NOT NULL CHECK (balance >= 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_payment_request UNIQUE (loan_id, request_id),
    CONSTRAINT uk_payment_period UNIQUE (loan_id, installment_number),
    CONSTRAINT payment_parts_match CHECK (amount = interest + principal)
);
