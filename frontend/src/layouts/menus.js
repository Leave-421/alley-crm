// 하단 탭과 위쪽 머리말. 탭 구성은 07-screens 「메뉴 (하단 탭)」(11-24 (4) 확정), 아이콘은 확정 목업을 따른다.
// end: true면 하위 주소에서는 켜지지 않는다(영역 첫 화면 탭). also: 그래도 같이 켜질 하위 주소(그 탭 안의 화면).
// defaultTitle: 화면이 route handle.title을 주지 않을 때 머리말 제목. areaChip: 제목 옆 역할 표시.

export const CUSTOMER_MENU = Object.freeze({
  defaultTitle: 'LOCORI',
  areaChip: null,
  notificationsTo: '/notifications', // G-11 알림함(A)
  tabs: [
    { to: '/', label: '홈', icon: 'home', end: true }, // G-14
    { to: '/reservations', label: '내 예약', icon: 'calendar' }, // G-04
    { to: '/my', label: '마이', icon: 'person' }, // G-10 (내 쿠폰 · 알림함 · 활동 기록 · 정책 · 역할 전환은 마이 안)
  ],
});

export const OWNER_MENU = Object.freeze({
  defaultTitle: 'LOCORI', // 목업은 가게 이름. 가게 정보를 읽는 화면 이슈에서 handle.title 대신 바꾼다
  areaChip: '사장님',
  notificationsTo: '/owner/notifications', // O-10 알림함(A)
  tabs: [
    { to: '/owner', label: '예약', icon: 'calendar', end: true, also: ['/owner/settings'] }, // O-01 (운영 설정 O-03 · 변경 요청 O-02는 예약 안)
    { to: '/owner/customers', label: '고객', icon: 'people' }, // O-04 · O-06
    { to: '/owner/flow', label: '손님 흐름', icon: 'flow' }, // O-07(A)
    { to: '/owner/settlement', label: '소개·정산', icon: 'money' }, // O-08
    { to: '/owner/coupons', label: '쿠폰', icon: 'gift' }, // O-09
  ],
});

export const ASSOCIATION_MENU = Object.freeze({
  defaultTitle: 'LOCORI', // 목업은 '대학가 상인회'(권역 이름). 상인회 정보를 읽는 화면 이슈에서 바꾼다
  areaChip: '상인회',
  notificationsTo: null, // 상인회 알림함은 07-screens에 없다(목업의 종은 정산으로 이어짐)
  tabs: [
    { to: '/association', label: '대시보드', icon: 'chart', end: true }, // A-01
    { to: '/association/stores', label: '가게 성과', icon: 'store' }, // A-02 (가게 관리 A-05는 가게 성과 안)
    { to: '/association/flow', label: '방문 흐름', icon: 'flow' }, // A-03(A)
    { to: '/association/settlement', label: '정산', icon: 'money' }, // A-04
  ],
});
