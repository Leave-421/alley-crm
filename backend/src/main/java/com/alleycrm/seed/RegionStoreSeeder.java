package com.alleycrm.seed;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * 권역 1개와 참여 가게 15곳을 넣는다(E1 #6). 표 · 칸은 D1(#12) 마이그레이션 {@code region} · {@code store} 그대로 쓴다.
 * 시드 가게는 가입 승인 · 영업 중 · 예약 받는 중으로 넣는다. 가상 가게라 실제 좌표는 비우고 검증 안 됨으로 둔다.
 */
@Component
@Profile("seed")
class RegionStoreSeeder {

    /** CONTRACTS.md 「3」 store.joinStatus · store.businessStatus 값. */
    static final String JOIN_APPROVED = "approved";
    static final String BUSINESS_OPEN = "open";

    private final JdbcTemplate jdbc;

    RegionStoreSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 넣은 권역 ID와, 시드 코드(S01 등) → 가게 ID. 가상 데이터(E16 · E17)가 시드 코드로 가게를 찾는다. */
    record Result(long regionId, Map<String, Long> storeIdsByCode) {
    }

    Result insert(SeedRegionData data) {
        long regionId = insertRegion(data.regionName());
        Map<String, Long> storeIds = new LinkedHashMap<>();
        for (SeedRegionData.Store store : data.stores()) {
            storeIds.put(store.code(), insertStore(regionId, store));
        }
        return new Result(regionId, storeIds);
    }

    private long insertRegion(String name) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO region (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private long insertStore(long regionId, SeedRegionData.Store s) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO store (
                        region_id, name, category, alley, map_x, map_y, real_lat, real_lng, real_verified, address,
                        join_status, business_status, reservation_enabled, open_time, close_time, closed_days,
                        slot_minutes, capacity_per_slot, booking_window_days, cancel_deadline_hours,
                        deposit_per_person, max_party_size)
                    VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, FALSE, ?, ?, ?, TRUE, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, regionId);
            ps.setString(i++, s.name());
            ps.setString(i++, s.category().code());
            ps.setString(i++, s.alley().code());
            ps.setInt(i++, s.mapX());
            ps.setInt(i++, s.mapY());
            ps.setString(i++, s.address());
            ps.setString(i++, JOIN_APPROVED);
            ps.setString(i++, BUSINESS_OPEN);
            // LocalTime을 그대로 넘긴다(java.sql.Time은 JVM 시간대 변환을 탈 수 있음).
            ps.setObject(i++, s.openTime());
            ps.setObject(i++, s.closeTime());
            ps.setString(i++, String.join(",", s.closedDays()));
            ps.setInt(i++, s.slotMinutes());
            ps.setInt(i++, s.capacityPerSlot());
            ps.setInt(i++, s.bookingWindowDays());
            ps.setInt(i++, s.cancelDeadlineHours());
            ps.setInt(i++, s.depositPerPerson());
            if (s.maxPartySize() == null) {
                ps.setNull(i, Types.INTEGER);
            } else {
                ps.setInt(i, s.maxPartySize());
            }
            return ps;
        }, key);
        return key.getKey().longValue();
    }
}
