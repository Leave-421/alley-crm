package com.alleycrm.analytics;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.store.StoreService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사장님 손님 흐름(O-07, FR-ANL-02, C17): 다른 가게 → 우리 가게(들어옴)와 우리 가게 → 다른 가게(나감)의 확인된 연계 방문 수.
 * 이동 정의는 상인회 방문 흐름(C20)과 같다. 다른 가게가 드러나므로 고유 손님 1 ~ 4명 묶음은 숨긴다.
 */
@Service
public class CustomerFlowService {

    private final StoreService stores;
    private final VisitFlowService flows;
    private final JdbcTemplate jdbc;

    CustomerFlowService(StoreService stores, VisitFlowService flows, JdbcTemplate jdbc) {
        this.stores = stores;
        this.flows = flows;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Flow flow(AccountPrincipal me) {
        long storeId = stores.requireApprovedOwnStore(me);
        long regionId = jdbc.queryForObject("SELECT region_id FROM store WHERE id = ?", Long.class, storeId);
        Map<Long, String> names = new HashMap<>();
        // 참여 가게(승인 · 탈퇴 신청 중)만. 탈퇴한 가게는 보여주지 않는다(C20과 같음)
        jdbc.query("SELECT id, name FROM store WHERE region_id = ? AND join_status IN ('approved', 'withdraw_requested')",
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                }, regionId);
        List<Row> inbound = new ArrayList<>();
        List<Row> outbound = new ArrayList<>();
        flows.moveCounts(regionId).forEach((pair, counts) -> {
            long from = pair.get(0);
            long to = pair.get(1);
            if (to == storeId && names.containsKey(from)) {
                inbound.add(new Row(from, counts[0], counts[1]));
            } else if (from == storeId && names.containsKey(to)) {
                outbound.add(new Row(to, counts[0], counts[1]));
            }
        });
        return new Flow(items(inbound, names), items(outbound, names));
    }

    /** 보이는 묶음은 많은 순, 숨긴 묶음은 뒤에 가게 ID 순(숨긴 값의 크기가 순서로 드러나지 않게) */
    private static List<StoreMoves> items(List<Row> rows, Map<Long, String> names) {
        return rows.stream()
                .sorted(Comparator.comparing((Row r) -> r.people() < MinimumGroup.MIN_PEOPLE)
                        .thenComparing(r -> r.people() < MinimumGroup.MIN_PEOPLE ? 0 : -r.count())
                        .thenComparingLong(Row::storeId))
                .map(r -> new StoreMoves(String.valueOf(r.storeId()), names.get(r.storeId()), MinimumGroup.mask(r.count(), r.people())))
                .toList();
    }

    private record Row(long storeId, long count, long people) {
    }

    /** 다른 가게 하나와의 확인된 연계 방문 수 */
    public record StoreMoves(String storeId, String storeName, MinimumGroup.Masked moves) {
    }

    /**
     * @param inbound 다른 가게에 들렀다가 같은 날 이어서 우리 가게에 온 이동
     * @param outbound 우리 가게에 들렀다가 같은 날 이어서 다른 가게로 간 이동
     */
    public record Flow(List<StoreMoves> inbound, List<StoreMoves> outbound) {
    }
}
