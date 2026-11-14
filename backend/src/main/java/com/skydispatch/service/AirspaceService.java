package com.skydispatch.service;

import com.skydispatch.common.BusinessException;
import com.skydispatch.common.GeoPoint;
import com.skydispatch.domain.entity.AirspaceZone;
import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.ZoneShape;
import com.skydispatch.domain.enums.ZoneType;
import com.skydispatch.dto.AirspaceDtos;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.dto.algorithm.AlgorithmDtos;
import com.skydispatch.repository.AirspaceZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 空域管理：禁飞区、限飞区、走廊。
 *
 * <p>本类承担一个关键职责：<b>把数据库里的空域翻译成算法服务能理解的障碍物描述</b>。
 * 因为空域可能用"圆心+半径"或"多边形"两种方式存储，而算法服务只认
 * {@code CIRCLE / POLYGON} 两种 shape，转换逻辑集中在这里，避免散落各处。
 *
 * <p>MVP 的简化：只有 {@code NO_FLY} 和 {@code RESTRICTED} 会作为路径规划的障碍物；
 * {@code WARNING} 只是提示，{@code CORRIDOR} 是推荐航路。真实系统还需要处理
 * 时间窗（effectiveFrom/To）和动态管制，这里先把结构留好。
 */
@Service
public class AirspaceService {

    private static final Logger log = LoggerFactory.getLogger(AirspaceService.class);

    /** 参与路径规划的障碍物类型。 */
    private static final List<ZoneType> OBSTACLE_TYPES =
            List.of(ZoneType.NO_FLY, ZoneType.RESTRICTED, ZoneType.BUILDING);

    private final AirspaceZoneRepository zoneRepository;

    public AirspaceService(AirspaceZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    /** 新增空域。 */
    @Transactional
    public AirspaceDtos.Response create(AirspaceDtos.ZoneRequest request) {
        /*
         * 编码、名称、严重度都可以不填 —— 它们要么是"系统的事"（编码必须唯一且合规范），
         * 要么能从区域类型**推导**出来（禁飞区天然比走廊严重）。
         * 让用户填这三样，换来的只是起名困难和一不小心全选了"高"。
         */
        ZoneNaming naming = ZONE_NAMING.getOrDefault(request.zoneType(),
                new ZoneNaming("ZN", "空域", ConflictSeverity.HIGH));
        boolean autoCode = request.code() == null || request.code().isBlank();
        String code = autoCode ? nextZoneCode(naming.prefix()) : request.code().trim();
        String name = request.name() == null || request.name().isBlank()
                ? naming.label() + " " + code
                : request.name().trim();
        ConflictSeverity severity = request.severity() == null ? naming.severity() : request.severity();

        zoneRepository.findByCode(code).ifPresent(z -> {
            throw BusinessException.invalidParam("空域编码已存在: " + code
                    + (autoCode ? "（自动生成时撞号，请重试）" : ""));
        });

        AirspaceZone zone = AirspaceZone.builder()
                .code(code)
                .name(name)
                .zoneType(request.zoneType())
                .shapeType(request.shapeType())
                .minAltitudeM(request.minAltitudeM() == null ? BigDecimal.ZERO : request.minAltitudeM())
                .maxAltitudeM(request.maxAltitudeM() == null ? BigDecimal.valueOf(120) : request.maxAltitudeM())
                .severity(severity)
                .enabled(request.enabled() == null || request.enabled())
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .remark(request.remark())
                .build();

        if (request.shapeType() == ZoneShape.CIRCLE) {
            if (request.center() == null || request.radiusM() == null) {
                throw BusinessException.invalidParam("圆形空域必须提供 center 和 radiusM");
            }
            zone.setCenterLat(request.center().lat());
            zone.setCenterLon(request.center().lon());
            zone.setRadiusM(request.radiusM());
        } else {
            if (request.points() == null || request.points().size() < 3) {
                throw BusinessException.invalidParam("多边形空域至少需要 3 个顶点");
            }
            // 顶点校验放在这里（而不是 DTO 上的 @Valid），原因见 AirspaceDtos.ZoneRequest 的注释
            for (CommonDtos.GeoDto point : request.points()) {
                if (point == null || point.lat() == null || point.lon() == null) {
                    throw BusinessException.invalidParam("多边形顶点必须包含 lat 与 lon");
                }
            }
            zone.setPolygonWkt(toWkt(request.points()));
        }

        AirspaceZone saved = zoneRepository.save(zone);
        log.info("新增空域: code={}, type={}, shape={}, 编码来源={}",
                saved.getCode(), saved.getZoneType(), saved.getShapeType(), autoCode ? "自动生成" : "用户指定");
        return AirspaceDtos.Response.from(saved, parsePolygonWkt(saved.getPolygonWkt()));
    }

    // ------------------------------------------------------------------
    // 自动命名（编码 / 名称 / 默认严重度）
    // ------------------------------------------------------------------

    /** 一个区域类型的"自动命名三件套"：编码前缀、中文名、默认严重度。 */
    private record ZoneNaming(String prefix, String label, ConflictSeverity severity) {
    }

    /**
     * 区域类型 → 自动命名规则。
     *
     * <p>前缀取行业惯例的英文缩写，编号顺延；严重度按"管得越死越严重"的直觉给默认值，
     * 避免以前那种"不管什么类型默认都是 HIGH"的糊弄。
     */
    private static final Map<ZoneType, ZoneNaming> ZONE_NAMING = Map.of(
            ZoneType.NO_FLY, new ZoneNaming("NFZ", "禁飞区", ConflictSeverity.CRITICAL),
            ZoneType.RESTRICTED, new ZoneNaming("RFA", "限飞区", ConflictSeverity.HIGH),
            ZoneType.CORRIDOR, new ZoneNaming("CRD", "航路走廊", ConflictSeverity.LOW),
            ZoneType.WARNING, new ZoneNaming("WRN", "警示区", ConflictSeverity.MEDIUM),
            ZoneType.BUILDING, new ZoneNaming("BLD", "建筑障碍", ConflictSeverity.HIGH));

    /**
     * 生成一个未被占用的区域编码（如 {@code NFZ-0003}）。
     *
     * <p>与 {@code DroneService#nextDroneCode} 同一套策略：从当前数量往后逐个试，
     * 编码列的唯一约束兜底，找不到时给出可读的失败信息。
     */
    private String nextZoneCode(String prefix) {
        int start = (int) zoneRepository.count();
        for (int i = start + 1; i < start + 10_000; i++) {
            String candidate = prefix + String.format("-%04d", i);
            if (zoneRepository.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw BusinessException.illegalState("无法自动生成空域编码：编号空间已用尽，请手动指定编码");
    }

    /** 查询全部空域。 */
    @Transactional(readOnly = true)
    public List<AirspaceDtos.Response> list() {
        return zoneRepository.findAll().stream()
                .map(z -> AirspaceDtos.Response.from(z, parsePolygonWkt(z.getPolygonWkt())))
                .toList();
    }

    /** 删除空域。 */
    @Transactional
    public void delete(Long id) {
        AirspaceZone zone = zoneRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("空域 id=" + id));
        zoneRepository.delete(zone);
        log.info("删除空域: code={}", zone.getCode());
    }

    /** 当前生效的空域（供调度/仿真使用）。 */
    @Transactional(readOnly = true)
    public List<AirspaceZone> activeZones() {
        return zoneRepository.findByEnabledTrue();
    }

    /**
     * 用空域编码换数据库主键。
     *
     * <p>算法服务只知道业务编码（Z-NF-001），而冲突表存的是外键 id，
     * 转换放在这里，避免让算法服务依赖数据库主键（那是很强的耦合）。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> idByCode(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        return zoneRepository.findByCode(code).map(AirspaceZone::getId);
    }

    /**
     * 转换成算法服务的障碍物列表。
     *
     * <p>只返回需要避让的类型，且必须具备可计算的几何信息。
     */
    @Transactional(readOnly = true)
    public List<AlgorithmDtos.ObstacleSpec> activeObstacleSpecs() {
        List<AlgorithmDtos.ObstacleSpec> specs = new ArrayList<>();
        for (AirspaceZone zone : activeZones()) {
            if (!OBSTACLE_TYPES.contains(zone.getZoneType())) {
                continue;
            }
            AlgorithmDtos.ObstacleSpec spec = toObstacleSpec(zone);
            if (spec != null) {
                specs.add(spec);
            }
        }
        return specs;
    }

    /**
     * 单个空域 → 障碍物描述。几何信息不完整时返回 null（宁可漏一个障碍，也不要让规划整套挂掉）。
     */
    public AlgorithmDtos.ObstacleSpec toObstacleSpec(AirspaceZone zone) {
        double minAlt = zone.getMinAltitudeM() == null ? 0 : zone.getMinAltitudeM().doubleValue();
        double maxAlt = zone.getMaxAltitudeM() == null ? 120 : zone.getMaxAltitudeM().doubleValue();

        if (zone.getShapeType() == ZoneShape.CIRCLE) {
            if (zone.getCenterLat() == null || zone.getCenterLon() == null || zone.getRadiusM() == null) {
                log.warn("圆形空域几何信息不完整，跳过: {}", zone.getCode());
                return null;
            }
            return new AlgorithmDtos.ObstacleSpec(
                    zone.getCode(), zone.getName(), "CIRCLE",
                    new AlgorithmDtos.LatLon(zone.getCenterLat(), zone.getCenterLon()),
                    zone.getRadiusM().doubleValue(), null, minAlt, maxAlt);
        }

        List<GeoPoint> polygon = parsePolygonWkt(zone.getPolygonWkt());
        if (polygon.size() < 3) {
            log.warn("多边形空域顶点不足，跳过: {}", zone.getCode());
            return null;
        }
        return new AlgorithmDtos.ObstacleSpec(
                zone.getCode(), zone.getName(), "POLYGON",
                null, null,
                polygon.stream().map(p -> new AlgorithmDtos.LatLon(p.lat(), p.lon())).toList(),
                minAlt, maxAlt);
    }

    // ------------------------------------------------------------------
    // WKT 互转
    // 存 WKT 而不是 PostGIS geometry，是为了让核心表不依赖 PostGIS。
    // 简单、可读、便于人工排查；需要空间索引时再执行 db/postgis 的增强脚本。
    // ------------------------------------------------------------------

    /** 点列表 → WKT 字符串，注意 WKT 的顺序是"经度 纬度"。 */
    public static String toWkt(List<CommonDtos.GeoDto> points) {
        StringBuilder sb = new StringBuilder("POLYGON((");
        for (int i = 0; i < points.size(); i++) {
            CommonDtos.GeoDto p = points.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(p.lon()).append(' ').append(p.lat());
        }
        // WKT 要求首尾闭环，这里自动补上第一个点
        CommonDtos.GeoDto first = points.get(0);
        sb.append(", ").append(first.lon()).append(' ').append(first.lat());
        sb.append("))");
        return sb.toString();
    }

    /** WKT 字符串 → 点列表。解析失败返回空列表，绝不抛异常。 */
    public static List<GeoPoint> parsePolygonWkt(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            return List.of();
        }
        try {
            String body = wkt.trim();
            int start = body.indexOf("((");
            int end = body.lastIndexOf("))");
            if (start < 0 || end < 0) {
                return List.of();
            }
            body = body.substring(start + 2, end);
            List<GeoPoint> points = new ArrayList<>();
            for (String pair : body.split(",")) {
                String[] parts = pair.trim().split("\\s+");
                if (parts.length >= 2) {
                    double lon = Double.parseDouble(parts[0]);
                    double lat = Double.parseDouble(parts[1]);
                    // WKT 首尾重复，去掉最后一个重复点
                    if (!points.isEmpty()
                            && points.get(points.size() - 1).lat() == lat
                            && points.get(points.size() - 1).lon() == lon) {
                        continue;
                    }
                    points.add(new GeoPoint(lat, lon));
                }
            }
            return points;
        } catch (RuntimeException e) {
            log.warn("解析 WKT 失败: {}", wkt, e);
            return List.of();
        }
    }
}
