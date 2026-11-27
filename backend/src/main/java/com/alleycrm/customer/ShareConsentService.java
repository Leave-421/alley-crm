package com.alleycrm.customer;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountRole;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
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
 * 공유 동의 기록(FR-CUS-01, C8)과 권역 공유 정책(FR-CUS-06, C11). 예약마다 동의 시각과 그 시점 권역 공유 정책의 항목을 남긴다.
 * 항목 값은 CONTRACTS.md 「3」 `shareItem`.
 */
@Service
public class ShareConsentService {

    /** 항상 공유, 끌 수 없음(11-24 (5)) */
    static final List<String> ALWAYS_SHARED = List.of("name", "phone", "visited_stores");
    /** 상인회가 켜고 끈다(FR-CUS-06). share_policy에 행이 없으면 기본값 켜짐 */
    static final List<String> CONFIGURABLE = List.of("party_size", "visit_datetime", "gender_age_group");
    /** 성별 · 연령대 항목. 끄면 추천(E)이 성별 · 연령대를 쓰지 않는다 */
    public static final String GENDER_AGE_GROUP = "gender_age_group";

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

    /** 그 권역에서 이 항목을 지금 가게끼리 공유하는지(꺼져 있지 않으면 공유) */
    @Transactional(readOnly = true)
    public boolean isShared(long regionId, String item) {
        return sharedItems(regionId).contains(item);
    }

    /** 상인회: 자기 권역 공유 항목 6개와 켜짐 여부, 계약 표 순서 */
    @Transactional(readOnly = true)
    public List<PolicyItem> policy(AccountPrincipal me) {
        me.requireRole(AccountRole.ASSOCIATION);
        List<String> shared = sharedItems(me.regionId());
        List<PolicyItem> items = new ArrayList<>();
        ALWAYS_SHARED.forEach(item -> items.add(new PolicyItem(item, true, true)));
        CONFIGURABLE.forEach(item -> items.add(new PolicyItem(item, shared.contains(item), false)));
        return items;
    }

    /**
     * 상인회: 자기 권역 항목 하나를 켜고 끈다(FR-CUS-06). 필수 항목(이름 · 연락처 · 방문한 가게 목록)은 끌 수 없다(#2 · #3).
     * 바꾼 값은 그 뒤 예약의 동의 기록과 추천에 바로 쓰인다. 이미 남은 동의 기록은 바꾸지 않는다.
     */
    @Transactional
    public List<PolicyItem> change(AccountPrincipal me, String item, boolean enabled) {
        me.requireRole(AccountRole.ASSOCIATION);
        if (ALWAYS_SHARED.contains(item)) {
            if (!enabled) {
                throw new ApiException(ErrorCode.CONFLICT, "이름 · 연락처 · 방문한 가게 목록은 끌 수 없는 필수 항목입니다.");
            }
            return policy(me);
        }
        if (!CONFIGURABLE.contains(item)) {
            throw new ApiException(ErrorCode.INVALID);
        }
        jdbc.update("""
                INSERT INTO share_policy (region_id, item, enabled) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)
                """, me.regionId(), item, enabled);
        return policy(me);
    }

    /** 공유 항목 하나: required면 끌 수 없다 */
    public record PolicyItem(String item, boolean enabled, boolean required) {
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
