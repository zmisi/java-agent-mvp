-- Seed emp schema (employees sample DB subset: 500 employees + related rows).
-- Regenerate: ./scripts/generate-docker-emp-seed.sh

\set ON_ERROR_STOP on

\copy emp.department FROM '/docker-entrypoint-initdb.d/data/department.csv' CSV
\copy emp.employee FROM '/docker-entrypoint-initdb.d/data/employee.csv' CSV
\copy emp.department_employee FROM '/docker-entrypoint-initdb.d/data/department_employee.csv' CSV
\copy emp.department_manager FROM '/docker-entrypoint-initdb.d/data/department_manager.csv' CSV
\copy emp.salary FROM '/docker-entrypoint-initdb.d/data/salary.csv' CSV
\copy emp.title FROM '/docker-entrypoint-initdb.d/data/title.csv' CSV

SELECT setval(
    'emp.id_employee_seq',
    COALESCE((SELECT MAX(id) FROM emp.employee), 1)
);
