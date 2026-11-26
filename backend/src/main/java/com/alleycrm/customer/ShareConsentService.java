package com.alleycrm.customer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 공유 동의 기록(FR-CUS-01, C8). 예약마다 동의 시각과 그 시점 권역 공유 정책의 항목을 남긴다.
 * 항목 값은 CONTRACTS.md 「3」 `shareItem`.
 */
@Service
public class ShareConsentService {

    /** 항상 공유, 끌 수 없음(11-24 (5)) */
    static final List<String> ALWAYS_SHARED = List.of("name", "phone", "visited_stores");
    /** 상인회가 켜고 끈다(FR-CUS-06). share_policy에 행이 없으면 기본값 켜짐 */
    static final List<String> CONFIGURABLE = List.of("party_size", "visit_datetime", "gender_age_group");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ShareConsentService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 그 예약의 동의 기록을 남긴다. 예약 신청과 같은 트랜잭션에서 부른다. */
    @Transactional
    public void record(long reservationId, long regionId, Instant consentedAt) {
        jdbc.update("INSERT INTO share_consent (reservation_id, consented_at, items) VALUES (?, ?, ?)",
                reservationId, LocalDateTime.ofInstant(consentedAt, ZoneOffset.UTC),
                json.writeValueAsString(sharedItems(regionId)));
    }

    /** 지금 권역 공유 정책에서 켜진 항목(항상 공유 항목 포함), 계약 표 순서. */
    List<String> sharedItems(long regionId) {
        List<String> off = jdbc.queryForList(
                "SELECT item FROM share_policy WHERE region_id = ? AND enabled = FALSE", String.class, regionId);
        List<String> items = new ArrayList<>(ALWAYS_SHARED);
        CONFIGURABLE.stream().filter(item -> !off.contains(item)).forEach(items::add);
        return items;
    }
}
