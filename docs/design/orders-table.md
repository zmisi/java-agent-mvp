# 订单表 (orders)

## 业务说明

存储电商订单主表，支持按用户、状态查询与分页。

## 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint | 主键，自增 |
| order_no | varchar(32) | 订单号，唯一 |
| user_id | bigint | 用户 ID |
| status | varchar(20) | 状态：PENDING / PAID / SHIPPED / CANCELLED |
| total_amount | numeric(12,2) | 订单总金额 |
| currency | varchar(3) | 币种，默认 CNY |
| remark | varchar(500) | 备注，可空 |

## 查询场景

1. 按 `user_id` + `status` 分页查询订单列表（按 createtime 倒序）
2. 按 `order_no` 精确查单
3. 按 `status` + 时间范围统计订单数

## 索引建议

- 唯一索引：`order_no`
- 复合索引：`(user_id, status, createtime DESC)`

## 规范

- 必须包含 `createtime`、`lastmodifiedtime`（timestamptz）
- `lastmodifiedtime` 需 UPDATE trigger 自动维护
