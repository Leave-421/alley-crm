package com.alleycrm.recommend.candidate;

import com.alleycrm.reservation.request.SlotRules;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천 후보 걸러내기(E4, FR-CRS-01, 05-policy 「다음 방문 추천」 1단계).
 *
 * <p>기준 가게 권역의 참여 가게에서 다음을 뺀다.
 * <ul>
 *   <li>기준 가게 자신</li>
 *   <li>예약을 받지 못하는 가게: 영업 상태가 휴업 · 중단이거나 예약 받기가 꺼진 가게</li>
 *   <li>손님이 같은 날 이미 예약한 가게(식별될 때만). 같은 날은 서비스 시간대로 기준 시각의 날짜다</li>
 * </ul>
 * 같은 업종 가게, 그날 빈 슬롯이 없는 가게, 쿠폰 행사 여부는 후보를 바꾸지 않는다. 순위는 E5 · E6 · E7이 정한다.
 */
@Service
public class CandidateService {

    private final CandidateRepository candidates;

    public CandidateService(CandidateRepository candidates) {
        this.candidates = candidates;
    }

    /**
     * 후보 가게 ID(오름차순, 중복 없음). {@code baseTime}은 지금(지도 추천) 또는 기준 가게 예약 시각(다음 코스 추천)이다.
     * 기준 가게가 없으면 빈 목록이다(기준 가게 검사는 부르는 쪽, 3.1).
     */
    @Transactional(readOnly = true)
    public List<Long> findCandidates(long baseStoreId, Instant baseTime, CandidateRequester requester) {
        List<Long> bookable = candidates.findBookableStoresInRegionOf(baseStoreId);
        if (bookable.isEmpty() || !requester.isIdentified()) {
            return bookable;
        }
        LocalDate day = LocalDate.ofInstant(baseTime, SlotRules.SERVICE_ZONE);
        Set<Long> reservedThatDay = new HashSet<>(candidates.findStoresReservedBetween(
                requester.memberCustomerId(), requester.guestPhone(), startOfDayUtc(day), startOfDayUtc(day.plusDays(1))));
        return bookable.stream().filter(storeId -> !reservedThatDay.contains(storeId)).toList();
    }

    private static LocalDateTime startOfDayUtc(LocalDate day) {
        return LocalDateTime.ofInstant(day.atStartOfDay(SlotRules.SERVICE_ZONE).toInstant(), ZoneOffset.UTC);
    }
}
