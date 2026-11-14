package com.skydispatch.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 地理计算工具测试。
 *
 * <p>这类纯函数最适合写单元测试：没有数据库、没有 Spring、跑得飞快，
 * 而且一旦改动公式能立刻发现回归。
 */
class GeoUtilsTest {

    /** 上海人民广场附近一个已知点。 */
    private static final double LAT = 31.2304;
    private static final double LON = 121.4737;

    @Test
    @DisplayName("haversine：1 度纬度约等于 111.19km")
    void haversineOneDegreeLatitude() {
        double distance = GeoUtils.haversineMeters(0, 0, 1, 0);
        assertThat(distance).isCloseTo(111194.93, within(1.0));
    }

    @Test
    @DisplayName("haversine：同一点距离为 0，且满足对称性")
    void haversineSamePointAndSymmetry() {
        assertThat(GeoUtils.haversineMeters(LAT, LON, LAT, LON)).isZero();
        double ab = GeoUtils.haversineMeters(LAT, LON, 31.2401, 121.4902);
        double ba = GeoUtils.haversineMeters(31.2401, 121.4902, LAT, LON);
        assertThat(ab).isCloseTo(ba, within(1e-9));
    }

    @Test
    @DisplayName("方位角：正北 0 度、正东 90 度")
    void bearing() {
        assertThat(GeoUtils.bearingDegrees(0, 0, 1, 0)).isCloseTo(0.0, within(0.01));
        assertThat(GeoUtils.bearingDegrees(0, 0, 0, 1)).isCloseTo(90.0, within(0.01));
    }

    @Test
    @DisplayName("点在多边形内：矩形内外与边界外都能正确判定")
    void pointInPolygon() {
        List<GeoPoint> square = List.of(
                new GeoPoint(31.20, 121.40),
                new GeoPoint(31.20, 121.50),
                new GeoPoint(31.30, 121.50),
                new GeoPoint(31.30, 121.40));
        assertThat(GeoUtils.pointInPolygon(31.25, 121.45, square)).isTrue();
        assertThat(GeoUtils.pointInPolygon(31.25, 121.39, square)).isFalse();
        assertThat(GeoUtils.pointInPolygon(31.35, 121.45, square)).isFalse();
        // 顶点不足 3 个时应安全返回 false，而不是抛异常
        assertThat(GeoUtils.pointInPolygon(31.25, 121.45, List.of(new GeoPoint(0, 0)))).isFalse();
    }

    @Test
    @DisplayName("折线参数化取点：中点、起点、终点、超界")
    void pointAtDistance() {
        List<GeoPoint> line = List.of(new GeoPoint(0, 0), new GeoPoint(0, 1));
        double total = GeoUtils.polylineLengthMeters(line);
        assertThat(total).isCloseTo(111194.93, within(1.0));

        assertThat(GeoUtils.pointAtDistance(line, 0).lon()).isCloseTo(0.0, within(1e-9));
        assertThat(GeoUtils.pointAtDistance(line, total / 2).lon()).isCloseTo(0.5, within(1e-3));
        // 超出总长度时应夹到终点，不能越界
        assertThat(GeoUtils.pointAtDistance(line, total * 2).lon()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("点到线段距离：垂足落在线段内/外两种情况")
    void distanceToSegment() {
        GeoPoint a = new GeoPoint(0, 0);
        GeoPoint b = new GeoPoint(0, 1);
        // 在线段正中间上方约 1km
        double mid = GeoUtils.distanceToSegmentMeters(new GeoPoint(0.01, 0.5), a, b);
        assertThat(mid).isCloseTo(1111.9, within(5.0));
        // 在线段外侧，应按端点距离算
        double beyond = GeoUtils.distanceToSegmentMeters(new GeoPoint(0, 2), a, b);
        assertThat(beyond).isCloseTo(111194.93, within(50.0));
    }
}
