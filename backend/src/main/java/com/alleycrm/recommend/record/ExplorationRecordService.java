package com.alleycrm.recommend.record;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.recommend.visitor.VisitorService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색 기록과 정식 코스 제안의 저장(E3, CONTRACTS_API 3.3). 모두 탐색 기록이고 소개 판정 · 수락률에 쓰지 않는다.
 * 단, 정식 코스 제안은 수락률 분모다(3.1 「기록과 집계 기준」).
 *
 * <p>노출 · 제안은 E7이 추천 후보 검사와 재계산을 마친 값으로 부른다. 여기서는 저장과 중복 처리만 한다.
 * 한 요청의 가게들은 한 트랜잭션에 저장한다.
 */
@Service
public class ExplorationRecordService {

    private final ExplorationRecordRepository records;
    private final VisitorService visitorService;
    private final Clock clock;

    public ExplorationRecordService(ExplorationRecordRepository records, VisitorService visitorService, Clock clock) {
        this.records = records;
        this.visitorService = visitorService;
        this.clock = clock;
    }

    /** 가게 클릭 한 번(FR-CRS-07). 누를 때마다 1건. 손님에게 보이지 않는 가게면 404. */
    @Transactional
    public void recordStoreClick(long storeId) {
        if (!records.isVisibleStore(storeId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        records.insertStoreClick(visitorService.currentVisitorId(), storeId, now());
    }

    /**
     * 지도 추천 노출(3.1 {@code POST /recommendation-exposures}의 저장). (방문자, 표시 ID, 가게)가 이미 있으면
     * 새로 만들지 않고 처음 행의 ID를 돌려준다. 방문자는 이 요청의 브라우저(없으면 새로 발급).
     */
    @Transactional
    public List<RecordedExposure> recordExposures(UUID displayId, long baseStoreId, int page, List<ExposureItem> items) {
        long visitorId = visitorService.currentVisitorId();
        LocalDateTime shownAt = now();
        return items.stream()
                .map(item -> new RecordedExposure(item.storeId(),
                        records.insertExposureIfAbsent(visitorId, displayId, baseStoreId, item, page, shownAt)))
                .toList();
    }

    /**
     * 정식 코스 제안(3.1 {@code POST /course-proposals}의 저장). (기준 예약, 가게)가 이미 있으면 새로 만들지 않고
     * 처음 행의 ID와 처음 표시 시각을 돌려준다.
     */
    @Transactional
    public List<RecordedProposal> recordProposals(long baseReservationId, List<Long> storeIds) {
        LocalDateTime shownAt = now();
        return storeIds.stream()
                .map(storeId -> records.insertProposalIfAbsent(baseReservationId, storeId, shownAt))
                .toList();
    }

    /**
     * 이 요청자의 추천 노출(쿠폰 다운로드, CONTRACTS_API 3.4). 이 브라우저의 방문자나 이 손님에 이어진 방문자의
     * 노출만 찾고, 다른 사람의 노출 · 없는 노출은 비어 있다. 방문자를 새로 발급하지 않는다.
     */
    @Transactional(readOnly = true)
    public Optional<ExposureSource> findOwnedExposure(long exposureId, long customerId) {
        Long browserVisitorId = visitorService.knownCurrentVisitorId().orElse(null);
        return records.findExposureOwnedBy(exposureId, browserVisitorId, customerId);
    }

    /** DB 칸이 DATETIME(6)이라 마이크로초까지만 쓴다. 값은 UTC. */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
