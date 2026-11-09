CREATE TABLE monthly_budget (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    entries JSONB NOT NULL
);
