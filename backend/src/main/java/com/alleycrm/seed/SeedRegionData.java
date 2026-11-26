package com.alleycrm.seed;

import java.time.LocalTime;
import java.util.List;

/**
 * 시드 파일 {@code seed/region-stores.json}의 모양. 권역 1개와 참여 가게 15곳.
 *
 * <p>좌표는 권역 전체에 하나뿐인 평면(가상, 미터 단위)이다. 추천의 거리 항(05-policy 「다음 방문 추천」 2단계)이
 * 다른 골목 가게 사이 거리도 쓰기 때문이다. 골목 안 그리는 순서는 같은 골목 가게를 {@code mapX} 순으로 정렬해 얻는다.
 * 이 좌표계는 A1 · D1과 협의 중인 잠정안이다(계약 3.1 미확정 R-9, 06-data는 "골목 안 표시 좌표").
 *
 * <p>가게는 모두 가상이라 실제 좌표를 넣지 않는다. 적재할 때 {@code real_lat} · {@code real_lng}는 비우고
 * {@code real_verified}는 거짓으로 둔다(NFR-07, FR-MAP-09 — 길찾기 대신 시연용 미리보기). 주소도 가상이다.
 */
public record SeedRegionData(String regionName, Plane plane, List<Store> stores) {

    /** 좌표 평면 크기(미터). 모든 가게 좌표는 0 이상, 이 값 이하. */
    public record Plane(int width, int height) {
    }

    /**
     * 가게 한 곳. 칸 뜻은 06-data STORE와 D1 마이그레이션을 따른다.
     *
     * @param code 시드 안에서만 쓰는 고정 키(예: S01). 가상 데이터(E16 · E17)가 DB ID 대신 이 값으로 가게를 가리킨다
     * @param name 가상 가게 이름(실제 지명 없음)
     * @param address 가상 주소(실제 지명 없음). 운영에서는 상인회가 관리(11-24 (5))
     * @param closedDays 휴무 요일(mon ~ sun). 빈 목록이면 연중무휴
     * @param cancelDeadlineHours 예약 시각 몇 시간 전까지 취소 가능한지. 24 이하(05-policy 「취소와 환불」)
     * @param depositPerPerson 인원당 예약금(원). 0 가능
     * @param maxPartySize 한 팀 최대 인원. {@code null}이면 제한 없음
     */
    public record Store(
            String code,
            String name,
            SeedCategory category,
            SeedAlley alley,
            int mapX,
            int mapY,
            String address,
            LocalTime openTime,
            LocalTime closeTime,
            List<String> closedDays,
            int slotMinutes,
            int capacityPerSlot,
            int bookingWindowDays,
            int cancelDeadlineHours,
            int depositPerPerson,
            Integer maxPartySize) {
    }
}
