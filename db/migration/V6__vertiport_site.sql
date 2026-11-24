-- 机巢站点台账：每个起降点可单独配置坪位数与地面周转时间。
-- 站点业务键 site_key 与调度运行态（VertiportService）使用的坐标键保持一致：
-- 纬度/经度各保留 5 位小数，格式 "31.23040,121.47370"。
CREATE TABLE IF NOT EXISTS vertiport_site (
    id                 BIGSERIAL PRIMARY KEY,
    site_key           VARCHAR(64)  NOT NULL UNIQUE,
    code               VARCHAR(32)  NOT NULL UNIQUE,
    name               VARCHAR(64)  NOT NULL,
    lat                DOUBLE PRECISION NOT NULL,
    lon                DOUBLE PRECISION NOT NULL,
    slots              INTEGER      NOT NULL DEFAULT 4,
    turnaround_seconds DOUBLE PRECISION NOT NULL DEFAULT 45,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

COMMENT ON TABLE vertiport_site IS '机巢/起降点资源台账（每站独立的坪位数与周转时间）';
COMMENT ON COLUMN vertiport_site.site_key IS '坐标业务键，与调度运行态一致';
COMMENT ON COLUMN vertiport_site.slots IS '停机位/起降坪数量';
COMMENT ON COLUMN vertiport_site.turnaround_seconds IS '降落后地面周转时间（仿真秒）';
