-- =============================================================================
--  sky-dispatch · 容器初始化脚本
--
--  挂载到 postgis 容器的 /docker-entrypoint-initdb.d/，
--  只在"数据卷为空、首次创建数据库"时执行一次。
--
--  作用：提前装好 PostGIS 扩展。V1 建表脚本并不依赖它，
--        但装好之后就可以随时执行 db/postgis/V3__postgis_enrichment.sql
--        开启空间索引与空间查询函数，不用再重建数据库。
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- 让应用账号拥有扩展对象的使用权（扩展由超级用户创建）
GRANT ALL ON SCHEMA public TO sky;

-- 顺手建几个便于排查的只读视图（可选，不需要可以删掉）
CREATE OR REPLACE VIEW v_database_info AS
SELECT current_database()                       AS database_name,
       current_user                             AS current_user,
       version()                                AS postgres_version,
       postgis_full_version()                   AS postgis_version,
       now()                                    AS checked_at;
