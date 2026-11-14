-- =============================================================================
--  V5：业务流水号改为数据库序列
--
--  订单号 / 任务号原先用"当日条数 + 1"生成，并发请求会算出同一个号；
--  改为从 seq_number 取号后，发号由数据库保证原子唯一。
--  number_sequence 表用于承载 JPA 序列取号实体，每取一个号插入一行。
-- =============================================================================

CREATE SEQUENCE IF NOT EXISTS seq_number START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS number_sequence (
    id BIGINT PRIMARY KEY
);

COMMENT ON SEQUENCE seq_number IS '订单号/任务号全局流水序列';
COMMENT ON TABLE number_sequence IS 'JPA 序列取号实体表（只用于分配编号）';
