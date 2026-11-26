/**
 * 시드 · 시연 데이터를 한 번에 초기화하고 다시 넣는 실행기 (담당 E — E1 · E17).
 * 권역 · 가게 15곳(E1), 상인회 계정(C2 단계 호출), 공공데이터(E2), 가상 데이터(E16 · E17)를 정해진 순서로 넣는다.
 * {@code seed} 프로필로만 켜지고, 확인 값 없이는 아무것도 지우지 않는다({@link com.alleycrm.seed.SeedSafetyGuard}).
 */
package com.alleycrm.seed;
