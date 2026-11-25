// 목 서버의 로그인 상태(브라우저 탭 메모리). 계정 목과 '로그인한 누구나' API 목이 같이 쓴다.
// 새로고침하면 로그아웃 상태로 돌아간다.

let currentAccount = null;

export function getMockAccount() {
  return currentAccount;
}

export function setMockAccount(account) {
  currentAccount = account;
}
