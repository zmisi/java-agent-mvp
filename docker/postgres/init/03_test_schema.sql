-- Isolated schema for DB release test deployments (same postgres instance as emp).
CREATE SCHEMA IF NOT EXISTS emp_test;

GRANT ALL ON SCHEMA emp_test TO agent;
