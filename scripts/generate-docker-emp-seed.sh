#!/usr/bin/env bash
# Regenerate docker/postgres/init data from local employees DB (emp schema).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$ROOT/docker/postgres/init/data"
# First 500 employees plus anyone referenced as a department manager (FK-safe subset).
SEED="(
  (SELECT id FROM emp.employee ORDER BY id LIMIT 500)
  UNION
  SELECT employee_id FROM emp.department_manager
)"
PSQL=(psql -h "${PGHOST:-127.0.0.1}" -d "${PGDATABASE:-employees}" -v ON_ERROR_STOP=1)

mkdir -p "$DATA"

"${PSQL[@]}" -c "\copy (SELECT * FROM emp.department ORDER BY id) TO '$DATA/department.csv' CSV"
"${PSQL[@]}" -c "\copy (SELECT e.* FROM emp.employee e WHERE e.id IN $SEED ORDER BY e.id) TO '$DATA/employee.csv' CSV"
"${PSQL[@]}" -c "\copy (SELECT de.* FROM emp.department_employee de WHERE de.employee_id IN $SEED ORDER BY de.employee_id, de.department_id) TO '$DATA/department_employee.csv' CSV"
"${PSQL[@]}" -c "\copy (SELECT dm.* FROM emp.department_manager dm WHERE dm.employee_id IN $SEED ORDER BY dm.employee_id, dm.department_id) TO '$DATA/department_manager.csv' CSV"
"${PSQL[@]}" -c "\copy (SELECT sa.* FROM emp.salary sa WHERE sa.employee_id IN $SEED ORDER BY sa.employee_id, sa.from_date) TO '$DATA/salary.csv' CSV"
"${PSQL[@]}" -c "\copy (SELECT t.* FROM emp.title t WHERE t.employee_id IN $SEED ORDER BY t.employee_id, t.title, t.from_date) TO '$DATA/title.csv' CSV"

echo "Wrote seed CSVs under $DATA"
wc -l "$DATA"/*.csv
