CREATE TABLE emp_test.orders (
  id BIGSERIAL PRIMARY KEY,
  order_no VARCHAR(32) NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  total_amount NUMERIC(12,2) NOT NULL,
  currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
  remark VARCHAR(500),
  createtime TIMESTAMPTZ NOT NULL DEFAULT now(),
  lastmodifiedtime TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION emp_test.update_lastmodifiedtime_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.lastmodifiedtime = now();
  RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_orders_lastmodifiedtime 
  BEFORE UPDATE ON emp_test.orders 
  FOR EACH ROW 
  EXECUTE FUNCTION emp_test.update_lastmodifiedtime_column();

CREATE UNIQUE INDEX idx_orders_order_no ON emp_test.orders (order_no);
CREATE INDEX idx_orders_user_id_status_createtime ON emp_test.orders (user_id, status, createtime DESC);