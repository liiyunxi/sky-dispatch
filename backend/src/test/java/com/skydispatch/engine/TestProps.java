package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;

import java.util.List;

/** 测试用配置构造器：只让被测参数成为变量，其余取与生产默认一致的字面量。 */
final class TestProps {

    private TestProps() {
    }

    static SkyProperties defaults() {
        return of(new SkyProperties.Dynamics(2.5, 0.9, 120.0, 0.5, 2.0, 1.5),
                new SkyProperties.Charging(80, 0.35),
                new SkyProperties.Vertiport(4, 45, 15),
                new SkyProperties.Strategic(true, 15, 2),
                new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
                new SkyProperties.Telemetry(true, 3.0, 0.8, 0.015, 1, 0.5));
    }

    static SkyProperties of(SkyProperties.Charging charging, SkyProperties.Vertiport vertiport,
                            SkyProperties.Strategic strategic, SkyProperties.Fault fault,
                            SkyProperties.Telemetry telemetry) {
        return of(new SkyProperties.Dynamics(2.5, 0.9, 120.0, 0.5, 2.0, 1.5),
                charging, vertiport, strategic, fault, telemetry);
    }

    static SkyProperties of(SkyProperties.Dynamics dynamics, SkyProperties.Charging charging,
                            SkyProperties.Vertiport vertiport, SkyProperties.Strategic strategic,
                            SkyProperties.Fault fault, SkyProperties.Telemetry telemetry) {
        return build(dynamics, charging, vertiport, strategic, fault, telemetry,
                new SkyProperties.Wind(false, 225, 2.5, 10, 0.14, 0.3, 41, 6, 0.55, 0.6, 2.2,
                        false, 1.05));
    }

    /** 只替换风场（用于 ETA 顺/逆风对比）。 */
    static SkyProperties withWind(SkyProperties.Wind wind) {
        return build(new SkyProperties.Dynamics(2.5, 0.9, 120.0, 0.5, 2.0, 1.5),
                new SkyProperties.Charging(80, 0.35),
                new SkyProperties.Vertiport(4, 45, 15),
                new SkyProperties.Strategic(true, 15, 2),
                new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
                new SkyProperties.Telemetry(true, 3.0, 0.8, 0.015, 1, 0.5),
                wind);
    }

    private static SkyProperties build(SkyProperties.Dynamics dynamics, SkyProperties.Charging charging,
                                       SkyProperties.Vertiport vertiport, SkyProperties.Strategic strategic,
                                       SkyProperties.Fault fault, SkyProperties.Telemetry telemetry,
                                       SkyProperties.Wind wind) {
        return new SkyProperties(
                new SkyProperties.Algorithm("http://localhost:8000", 2000, 10000, false),
                new SkyProperties.Simulation(true, 1000, 8.0, 5, 5, 2, 10000,
                        false, 0.25, 98, 60, false, 3, 2, 60),
                new SkyProperties.Dispatch("NEAREST_AVAILABLE", 30000, 25,
                        1.0, 0.5, 0.3, false, 90, 1.15),
                new SkyProperties.Flight(80, 50, 30, 4, 3, 80,
                        List.of(62.0, 80.0, 98.0, 116.0)),
                new SkyProperties.Energy(1.5, 2.5, 0.30, 60.0),
                wind,
                new SkyProperties.Bootstrap(false),
                new SkyProperties.Auth(false, "admin", "test", "test-secret", 60),
                dynamics, charging, vertiport, strategic, fault, telemetry);
    }
}
