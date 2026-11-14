package com.skydispatch.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.function.Function;

/**
 * 跨模块复用的 DTO 容器。
 *
 * <p>为什么把多个 record 放在一个文件里？因为它们都属于同一个"数据形状族"。
 * Java 只允许一个 public 顶层类型，把强相关的小 record 收进一个容器类，
 * 可以避免出现几十个只有三行的文件。使用方式：{@code CommonDtos.GeoDto}。
 */
public final class CommonDtos {

    private CommonDtos() {
    }

    /**
     * 地理坐标 + 可选地址文本。
     *
     * <p>经纬度用包装类型 {@code Double} 而不是 {@code double}：
     * 只有包装类型才能被 {@code @NotNull} 校验出"用户没传"这种情况。
     */
    public record GeoDto(
            @NotNull(message = "不能为空") @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull(message = "不能为空") @DecimalMin("-180") @DecimalMax("180") Double lon,
            String address
    ) {
    }

    /** 统一分页响应，前端不用关心 Spring Data 的 Page 序列化格式。 */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

        public static <E, T> PageResponse<T> of(List<T> content, org.springframework.data.domain.Page<E> page) {
            return new PageResponse<>(content, page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }

        public static <E, T> PageResponse<T> of(org.springframework.data.domain.Page<E> page, Function<E, T> mapper) {
            return of(page.getContent().stream().map(mapper).toList(), page);
        }
    }

    /** 简单的"操作结果"响应，用于 DELETE / PATCH 这类没有实体返回的接口。 */
    public record OperationResult(boolean success, String message) {
        public static OperationResult ok(String message) {
            return new OperationResult(true, message);
        }
    }
}
