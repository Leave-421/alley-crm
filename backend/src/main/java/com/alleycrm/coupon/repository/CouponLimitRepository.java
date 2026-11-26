package com.alleycrm.coupon.repository;

import com.alleycrm.coupon.domain.CouponStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 쿠폰 발급 제한 판단에 쓰는 읽기(CONTRACTS_API 3.4). 시각은 UTC 값이다.
 *
 * <p>가게 표(C 영역)는 읽기만 한다: 손님에게 보이는지 · 영업 중인지 · 영업 상태가 바뀐 시각(휴업 뒤 제한 해제).
 */
@Repository
public class CouponLimitRepository {

    /** 발급 제한 판단에 필요한 가게 값. */
    public record TargetStore(long id, String businessStatus, Instant businessStatusChangedAt) {

        public boolean isOpen() {
            return "open".equals(businessStatus);
        }
    }

    /** 같은 회원 · 같은 행사에서 전에 받은 쿠폰 한 장. */
    public record PastDownload(Instant downloadedAt, CouponStatus status) {
    }

    private static final String PAST_DOWNLOADS = """
            SELECT downloaded_at, status FROM coupon
            WHERE customer_id = ? AND coupon_policy_id = ? AND (? IS NULL OR downloaded_at >= ?)
            """;

    private static final RowMapper<PastDownload> PAST_DOWNLOAD = (rs, n) -> new PastDownload(
            utc(rs.getObject("downloaded_at", LocalDateTime.class)),
            CouponStatus.fromCode(rs.getString("status"))
                    .orElseThrow(() -> new IllegalStateException("알 수 없는 coupon.status")));

    private final JdbcTemplate jdbc;

    CouponLimitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 손님에게 보이는 가게(승인된 가게)만. 예약 신청(D2) · 탐색 기록(E3)과 같은 기준. */
    public Optional<TargetStore> findVisibleStore(long storeId) {
        List<TargetStore> rows = jdbc.query("""
                SELECT id, business_status, business_status_changed_at FROM store
                WHERE id = ? AND join_status = 'approved'
                """,
                (rs, n) -> new TargetStore(rs.getLong("id"), rs.getString("business_status"),
                        utc(rs.getObject("business_status_changed_at", LocalDateTime.class))),
                storeId);
        return rows.stream().findFirst();
    }

    /**
     * 같은 회원 · 같은 행사에서 받은 쿠폰. {@code since}(영업 상태가 바뀐 시각, 없으면 null) 전에 받은 것은 뺀다.
     * 쿠폰 상세 표시용(잠그지 않음).
     */
    public List<PastDownload> findPastDownloads(long customerId, long couponPolicyId, Instant since) {
        LocalDateTime sinceUtc = utc(since);
        return jdbc.query(PAST_DOWNLOADS, PAST_DOWNLOAD, customerId, couponPolicyId, sinceUtc, sinceUtc);
    }

    /**
     * {@link #findPastDownloads}의 잠그는 읽기. 다운로드 트랜잭션에서 발급 수 조건부 UPDATE 뒤에 불러,
     * 먼저 커밋된 같은 회원의 다운로드를 놓치지 않는다.
     */
    public List<PastDownload> findPastDownloadsLocked(long customerId, long couponPolicyId, Instant since) {
        LocalDateTime sinceUtc = utc(since);
        return jdbc.query(PAST_DOWNLOADS + " FOR SHARE", PAST_DOWNLOAD, customerId, couponPolicyId, sinceUtc, sinceUtc);
    }

    /** DB의 DATETIME 칸은 UTC 시각 그대로다(JdbcTemplate은 시간대를 바꾸지 않는다). */
    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
