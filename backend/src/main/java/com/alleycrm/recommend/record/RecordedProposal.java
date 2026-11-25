package com.alleycrm.recommend.record;

import java.time.LocalDateTime;

/** 저장한(또는 이미 있던) 정식 코스 제안. 3.1 응답의 {@code courseProposalId} · {@code firstShownAt}(UTC 값). */
public record RecordedProposal(long storeId, long courseProposalId, LocalDateTime firstShownAt) {
}
