UPDATE monthly_budget
SET entries = entries || (
    SELECT jsonb_object_agg(group_name, with_ids)
    FROM (
        SELECT group_name, COALESCE(
            jsonb_agg(item || jsonb_build_object('id', gen_random_uuid()) ORDER BY position)
                FILTER (WHERE item IS NOT NULL), '[]'::jsonb
        ) AS with_ids
        FROM unnest(ARRAY['incomes', 'expenses', 'oneOffs']) AS groups(group_name)
        LEFT JOIN LATERAL jsonb_array_elements(entries -> group_name) WITH ORDINALITY AS items(item, position)
            ON TRUE
        GROUP BY group_name
    ) AS groups_with_ids
);

CREATE TABLE budget_calendar_marks (
    entry_id UUID NOT NULL,
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    PRIMARY KEY (entry_id, month)
);
