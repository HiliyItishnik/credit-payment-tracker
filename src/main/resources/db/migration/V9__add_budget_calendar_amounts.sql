CREATE TABLE budget_calendar_amounts (
    entry_id UUID NOT NULL,
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    amount NUMERIC(16, 2) NOT NULL CHECK (amount > 0 AND amount <= 1000000000),
    PRIMARY KEY (entry_id, month)
);
