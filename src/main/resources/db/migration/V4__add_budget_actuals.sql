CREATE TABLE budget_actuals (
    month DATE PRIMARY KEY CHECK (EXTRACT(DAY FROM month) = 1),
    actual JSONB NOT NULL,
    planned JSONB NOT NULL
);
