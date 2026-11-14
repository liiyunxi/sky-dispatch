package com.skydispatch.common;

/**
 * 一个不可变的经纬度点。
 *
 * <p>用 record 而不是普通类：Java 16+ 的 record 自动生成构造器、getter、equals/hashCode，
 * 非常适合这种"只装数据"的小对象，也天然不可变、线程安全。
 *
 * <p>注意纬度(lat)在前、经度(lon)在后，这是本项目统一约定（和 SQL 里的列顺序一致）。
 *
 * @param lat 纬度，范围 [-90, 90]
 * @param lon 经度，范围 [-180, 180]
 */
public record GeoPoint(double lat, double lon) {

    /** 到另一个点的地表距离（米）。 */
    public double distanceTo(GeoPoint other) {
        return GeoUtils.haversineMeters(this.lat, this.lon, other.lat, other.lon);
    }

    /** 方位角（度，正北为 0，顺时针增加）。 */
    public double bearingTo(GeoPoint other) {
        return GeoUtils.bearingDegrees(this.lat, this.lon, other.lat, other.lon);
    }

    @Override
    public String toString() {
        return String.format("(%.5f, %.5f)", lat, lon);
    }
}
