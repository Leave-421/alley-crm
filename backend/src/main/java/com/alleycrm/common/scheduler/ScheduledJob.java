package com.alleycrm.common.scheduler;

/**
 * 사람이 누르지 않아도 도는 자동 작업 하나(D7, NFR-06). 스프링 빈으로 두면 {@link ScheduledJobRunner}가 주기적으로 부른다.
 * 각 작업은 서버 시계(D18)로 '지금 해야 할 대상'을 찾아 처리하고, 처리한 건수를 돌려준다. 여러 번 불려도 결과가 같아야 한다.
 * 예: 자동 거절 · 자동 취소(D3 · D4), 쿠폰 만료(E14), 상인회 쿠폰 예산 해제(D20), 비회원 정보 파기(C12).
 */
public interface ScheduledJob {

    /** 로그 · 응답에 쓰는 이름(소문자 snake) */
    String name();

    /** @return 처리한 건수 */
    int run();
}
