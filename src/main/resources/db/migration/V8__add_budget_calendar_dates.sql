CREATE TABLE budget_calendar_dates (
    entry_id UUID NOT NULL,
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    scheduled_date DATE NOT NULL CHECK (scheduled_date BETWEEN DATE '1900-01-01' AND DATE '2200-12-31'),
    PRIMARY KEY (entry_id, month)
);
