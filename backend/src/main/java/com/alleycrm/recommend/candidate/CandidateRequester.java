package com.alleycrm.recommend.candidate;

/**
 * 추천을 요청한 손님의 식별(CONTRACTS_API 3.1 「손님 식별에 따른 차이」). '같은 날 이미 예약한 가게'를 뺄 때만 쓴다.
 * 세션에서 정해 넘기고, 요청 인자로 받지 않는다.
 *
 * <ul>
 *   <li>회원: 손님 ID의 예약</li>
 *   <li>비회원 제한 세션: 그 전화번호의 (파기되지 않은) 비회원 예약</li>
 *   <li>익명 방문자 · 사장님 · 상인회: 식별하지 않아 빼지 않는다</li>
 * </ul>
 */
public record CandidateRequester(Long memberCustomerId, String guestPhone) {

    public static final CandidateRequester UNIDENTIFIED = new CandidateRequester(null, null);

    public static CandidateRequester member(long customerId) {
        return new CandidateRequester(customerId, null);
    }

    public static CandidateRequester guest(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("비회원 식별에는 전화번호가 필요합니다.");
        }
        return new CandidateRequester(null, phone);
    }

    boolean isIdentified() {
        return memberCustomerId != null || guestPhone != null;
    }
}
