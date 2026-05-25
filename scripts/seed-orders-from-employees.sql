-- Seed emp_test.orders from 10 employees in emp.employee (5–100 orders each).
-- Run: psql -d employees -f scripts/seed-orders-from-employees.sql

\set ON_ERROR_STOP on

WITH picked_employees AS (
    SELECT id
    FROM emp.employee
    ORDER BY id
    LIMIT 10
),
order_counts AS (
    SELECT id, 5 + floor(random() * 96)::int AS order_count
    FROM picked_employees
),
order_slots AS (
    SELECT c.id AS user_id, gs.i AS seq
    FROM order_counts c
    CROSS JOIN LATERAL generate_series(1, c.order_count) AS gs(i)
)
INSERT INTO emp_test.orders (order_no, user_id, status, total_amount, currency, remark, createtime)
SELECT
    format('ORD-%s-%s-%s', s.user_id, s.seq, lpad(to_hex((random() * 4294967295)::bigint), 8, '0')),
    s.user_id,
    (ARRAY['PENDING', 'PAID', 'SHIPPED', 'CANCELLED'])[1 + floor(random() * 4)::int],
    round((10 + random() * 4990)::numeric, 2),
    'CNY',
    CASE WHEN random() < 0.25 THEN 'seed order for employee ' || s.user_id ELSE NULL END,
    now() - (random() * interval '365 days')
FROM order_slots s;

SELECT user_id, count(*) AS order_count
FROM emp_test.orders
WHERE user_id IN (SELECT id FROM emp.employee ORDER BY id LIMIT 10)
GROUP BY user_id
ORDER BY user_id;
