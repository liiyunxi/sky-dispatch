package com.skydispatch.common;

import java.util.List;

/**
 * 地理计算工具类（纯静态方法，无状态）。
 *
 * <p>为什么用 haversine 而不是简单的勾股定理？因为经纬度是角度不是米。
 * haversine 公式把地球当球体，在几公里到几十公里的配送场景里误差 &lt; 0.5%，完全够用。
 * 需要更高精度时应交给 PostGIS 的 geography 类型（见 db/postgis 脚本）。
 *
 * <p>地球平均半径 6371008.8 米（IUGG 推荐值）。
 */
public final class GeoUtils {

    /** 地球平均半径（米）。 */
    public static final double EARTH_RADIUS_M = 6371008.8;

    private GeoUtils() {
        // 工具类不允许实例化
    }

    /**
     * 两点间地表大圆距离（米）。
     */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * 方位角（度）：从点 1 指向点 2 的方向，正北为 0，顺时针增加，用于设置无人机机头朝向。
     */
    public static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLambda = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /**
     * 线性插值：返回从 a 到 b 之间比例为 t 的点（t=0 返回 a，t=1 返回 b）。
     * 小范围（几公里）内直接对经纬度做线性插值，误差可忽略。
     */
    public static GeoPoint interpolate(GeoPoint a, GeoPoint b, double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        return new GeoPoint(a.lat() + (b.lat() - a.lat()) * clamped,
                a.lon() + (b.lon() - a.lon()) * clamped);
    }

    /** 沿着一条折线（航路）按已飞行距离取位置，用于仿真推进。 */
    public static GeoPoint pointAtDistance(List<GeoPoint> polyline, double distanceMeters) {
        if (polyline == null || polyline.isEmpty()) {
            throw new IllegalArgumentException("polyline 不能为空");
        }
        if (polyline.size() == 1 || distanceMeters <= 0) {
            return polyline.get(0);
        }
        double travelled = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            GeoPoint prev = polyline.get(i - 1);
            GeoPoint cur = polyline.get(i);
            double seg = prev.distanceTo(cur);
            if (travelled + seg >= distanceMeters) {
                double t = seg <= 0 ? 0 : (distanceMeters - travelled) / seg;
                return interpolate(prev, cur, t);
            }
            travelled += seg;
        }
        return polyline.get(polyline.size() - 1);
    }

    /** 折线总长度（米）。 */
    public static double polylineLengthMeters(List<GeoPoint> polyline) {
        double total = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            total += polyline.get(i - 1).distanceTo(polyline.get(i));
        }
        return total;
    }

    /**
     * 射线法判断点是否在多边形内部（多边形顶点按顺序给出，首尾不必闭合）。
     */
    public static boolean pointInPolygon(double lat, double lon, List<GeoPoint> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            double yi = polygon.get(i).lat();
            double xi = polygon.get(i).lon();
            double yj = polygon.get(j).lat();
            double xj = polygon.get(j).lon();
            boolean intersects = ((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * 点到线段的最短距离（米）。用"局部平面近似"把经纬度转成米再算，简单且足够准。
     */
    public static double distanceToSegmentMeters(GeoPoint p, GeoPoint a, GeoPoint b) {
        double latScale = Math.toRadians(1.0) * EARTH_RADIUS_M;                       // 1 度纬度 ≈ 111.32 km
        double lonScale = latScale * Math.cos(Math.toRadians((a.lat() + b.lat()) / 2)); // 经度随纬度收缩
        double px = (p.lon() - a.lon()) * lonScale;
        double py = (p.lat() - a.lat()) * latScale;
        double bx = (b.lon() - a.lon()) * lonScale;
        double by = (b.lat() - a.lat()) * latScale;
        double lenSq = bx * bx + by * by;
        double t = lenSq <= 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / lenSq));
        double dx = px - bx * t;
        double dy = py - by * t;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 判断点是否落在圆形区域内。 */
    public static boolean pointInCircle(GeoPoint p, GeoPoint center, double radiusMeters) {
        return p.distanceTo(center) <= radiusMeters;
    }

    /**
     * 从给定点沿指定方位角移动 distanceMeters 后的坐标（等距方位投影，局部平面近似）。
     *
     * <p>配送范围只有几公里，用"米 → 经纬度增量"的平面近似误差可忽略；
     * 用于自动改航时在航线两侧生成侧向偏移航路点。
     */
    public static GeoPoint destinationPoint(GeoPoint from, double bearingDegrees, double distanceMeters) {
        double latScale = Math.toRadians(1.0) * EARTH_RADIUS_M;
        double lonScale = latScale * Math.cos(Math.toRadians(from.lat()));
        double bearing = Math.toRadians(bearingDegrees);
        double northMeters = Math.cos(bearing) * distanceMeters;
        double eastMeters = Math.sin(bearing) * distanceMeters;
        return new GeoPoint(
                from.lat() + northMeters / latScale,
                from.lon() + eastMeters / lonScale);
    }

    /** 把纬经度格式化，日志里用。 */
    public static String format(double lat, double lon) {
        return String.format("(%.5f, %.5f)", lat, lon);
    }
}
