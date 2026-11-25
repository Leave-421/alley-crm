package com.alleycrm.store;

/** 가게 가입 승인 · 거절이 정해짐. 커밋 뒤 C14가 그 가게 사장님에게 store_join_result 알림을 남긴다. */
public record StoreJoinDecidedEvent(long storeId, String joinStatus) {
}
