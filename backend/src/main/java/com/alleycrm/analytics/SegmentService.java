package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.store.StoreService;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사장님 세그먼트 분석(O-06, FR-ANL-01, C16). 우리 가게를 한 번이라도 방문한 손님을 '손님이 이용한 전체 가게 중
 * 우리 가게를 이용한 비율'(우리 가게 방문 처리 수 ÷ 권역 전체 방문 처리 수)로 네 묶음에 나눈다.
 * 익명 묶음 숫자만 주고, 5명 미만 숨김은 적용하지 않는다(11-23 (2), FR-ANL-01 #1).
 */
@Service
public class SegmentService {

    /** 묶음 값과 경계(팀이 정한 값, 바꾸려면 여기만 고친다). 순서는 응답 순서와 같다 */
    public enum Segment {
        /** 우리 가게만(100%) */
        EXCLUSIVE("exclusive"),
        /** 주로 우리 가게(50% 이상) */
        MOSTLY("mostly"),
        /** 가끔(25% 이상) */
        SOMETIMES("sometimes"),
        /** 드물게(25% 미만) */
        RARELY("rarely");

        private final String value;

        Segment(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /** 정수로만 비교해 경계값(50% · 25%)이 소수 오차 없이 위 구간에 들어간다 */
        static Segment of(long mine, long total) {
            if (mine == total) {
                return EXCLUSIVE;
            }
            if (mine * 2 >= total) {
                return MOSTLY;
            }
            if (mine * 4 >= total) {
                return SOMETIMES;
            }
            return RARELY;
        }
    }

    private final StoreService stores;
    private final JdbcTemplate jdbc;

    SegmentService(StoreService stores, JdbcTemplate jdbc) {
        this.stores = stores;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Segments segments(AccountPrincipal me) {
        long storeId = stores.requireApprovedOwnStore(me);
        long[][] sums = new long[Segment.values().length][2];
        // 손님마다 우리 가게 · 권역 전체 방문 처리 수(탈퇴한 가게 방문도 '이용한 가게'에 든다)
        jdbc.query("""
                SELECT SUM(r.store_id = ?) AS mine, COUNT(*) AS total
                FROM reservation r JOIN store s ON s.id = r.store_id
                WHERE r.status = 'visited' AND s.region_id = (SELECT region_id FROM store WHERE id = ?)
                GROUP BY r.customer_id
                HAVING mine > 0
                """, rs -> {
                    long mine = rs.getLong("mine");
                    int i = Segment.of(mine, rs.getLong("total")).ordinal();
                    sums[i][0]++;
                    sums[i][1] += mine;
                }, storeId, storeId);
        List<Row> rows = Arrays.stream(Segment.values())
                .map(s -> new Row(s.value(), sums[s.ordinal()][0], sums[s.ordinal()][1])).toList();
        return new Segments(rows.stream().mapToLong(Row::customerCount).sum(), rows);
    }

    /**
     * @param customerCount 이 묶음 손님 수(숨기지 않음)
     * @param visitCount 이 묶음 손님들의 우리 가게 방문 처리 수
     */
    public record Row(String segment, long customerCount, long visitCount) {
    }

    /** @param totalCustomers 우리 가게를 한 번이라도 방문한 손님 수 */
    public record Segments(long totalCustomers, List<Row> segments) {
    }
}
