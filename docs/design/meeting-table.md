# MEETTING表 (meeting)

## 业务说明

online meeting like Webex meeting, Zoom meeting.

## 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| meetingid | bigint | 主键，自增 |
| meetingname | varchar(32) | 订单号，唯一 |
| host_id | bigint | 用户 ID |
| status | varchar(20) | 状态：SCHEDULED / INPROGRESS / COMPLETED / CANCELLED |
| starttime | timestamp | 订单总金额 |
| endtime | timestamp | 币种，默认 CNY |
| meetinglink | varchar(500) | 备注，可空 |

## 查询场景

1. 按 `host_id` + `status` 分页查询MEETING列表（按 starttime 倒序）

## 索引建议

- 复合索引：`(host_id, status, starttime DESC)`

## 规范

- 必须包含 `createtime`、`lastmodifiedtime`（timestamptz）
- `lastmodifiedtime` 需 UPDATE trigger 自动维护
