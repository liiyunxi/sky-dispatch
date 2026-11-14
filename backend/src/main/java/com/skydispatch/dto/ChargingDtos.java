package com.skydispatch.dto;

import com.skydispatch.domain.entity.ChargingStation;

/**
 * 充电站对外契约。
 *
 * <p>不直接返回实体：实体的 created/updated 时间戳对前端没用，
 * 而且契约一旦绑定实体，改表结构就会顺手改坏接口。
 */
public final class ChargingDtos {

    private ChargingDtos() {
    }

    /** 充电站信息。 */
    public record Station(
            Long id,
            String code,
            String name,
            String city,
            Double lat,
            Double lon,
            Integer chargeSlots,
            Boolean enabled,
            String remark
    ) {
        public static Station from(ChargingStation station) {
            return new Station(
                    station.getId(),
                    station.getCode(),
                    station.getName(),
                    station.getCity(),
                    station.getLat(),
                    station.getLon(),
                    station.getChargeSlots(),
                    station.getEnabled(),
                    station.getRemark());
        }
    }
}
