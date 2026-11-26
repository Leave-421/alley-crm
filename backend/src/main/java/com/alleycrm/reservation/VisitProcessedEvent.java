package com.alleycrm.reservation;

import java.time.Instant;

/**
 * '방문 처리됨' 이벤트(D11, FR-RSV-09). 방문 처리한 트랜잭션 안에서 나간다.
 * 받는 쪽은 D11 코드를 고치지 않고 자기 파일에서 처리한다(기획 00-backlog 「같이 쓰는 것 규칙」).
 * <ul>
 *   <li>소개 확정 · 수수료(D15) · 쿠폰 할인 기록(E14): {@code @EventListener} — 같은 트랜잭션,
 *       실패하면 방문 처리도 되돌아간다. 소개 후보 판정은 신청 때 D12(단계 40)가 이미 했고, E15는 이 이벤트를 받지 않는다</li>
 *   <li>방문 금액 알림(D21, C14): {@code @TransactionalEventListener} — 커밋 뒤</li>
 * </ul>
 * 쿠폰 사용 완료는 이 이벤트로 바꾸지 않는다. 사장님이 따로 사용 확인한다(FR-CPN-08).
 *
 * @param visitAmount 사장님이 넣은 결제 금액(쿠폰 할인 전, 원)
 * @param courseProposalId 소개 후보면 정식 코스 제안 id, 아니면 null
 * @param senderStoreId 소개 후보면 보낸 가게 id, 아니면 null(응답 · 알림에 내보내지 않는다)
 */
public record VisitProcessedEvent(
        long reservationId,
        long storeId,
        long customerId,
        int visitAmount,
        Instant visitedAt,
        Long courseProposalId,
        Long senderStoreId) {
}
