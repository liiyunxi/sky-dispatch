-- =============================================================================
--  V4：订单 / 任务增加乐观锁版本号
--
--  背景：这两个实体同时会被仿真线程（推进/送达/失败）和 HTTP 线程（派单/取消/中止）修改。
--  没有版本号时，后提交的事务会静默覆盖前一个事务的结果，
--  典型症状是"订单已经取消，却又被仿真改回配送中"。
--  加 version 后，冲突方更新会失败（影响 0 行），由业务层决定重试或报错。
-- =============================================================================

ALTER TABLE delivery_order
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE dispatch_task
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN delivery_order.version IS '乐观锁版本号';
COMMENT ON COLUMN dispatch_task.version IS '乐观锁版本号';
