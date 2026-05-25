# invitee表 (invitee)

## 业务说明

invitee that invited in a meeting

## 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| inviteeid | bigint | 主键，自增 |
| meetingid | bigint 
| inviteename | varchar(32) | 订单号，唯一 |
| status | varchar(20) | 状态：PENDING / INVITED / REJECTED |

## 查询场景

1. 按 `meetingid` 分页查询

## 索引建议

- 复合索引：`(meetingid)`

## 规范

- 必须包含 `createtime`、`lastmodifiedtime`（timestamptz）
- `lastmodifiedtime` 需 UPDATE trigger 自动维护
