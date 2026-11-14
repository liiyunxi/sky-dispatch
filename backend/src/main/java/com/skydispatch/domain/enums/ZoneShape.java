package com.skydispatch.domain.enums;

/**
 * 空域几何形状。
 *
 * <p>MVP 阶段不强制依赖 PostGIS，于是把几何分为两类：
 * 圆用"中心点 + 半径"存普通列，多边形用 WKT 文本列存，靠 shapeType 决定读哪几列。
 */
public enum ZoneShape {

    /** 圆形：使用 centerLat / centerLon / radiusM。 */
    CIRCLE,

    /** 多边形：使用 polygonWkt 文本。 */
    POLYGON
}
