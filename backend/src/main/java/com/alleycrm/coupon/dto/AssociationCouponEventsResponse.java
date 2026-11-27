package com.alleycrm.coupon.dto;

import java.util.List;

/** {@code GET /api/v1/association/coupon-events} 응답: 그 권역 행사 전부, 최근에 만든 순. */
public record AssociationCouponEventsResponse(List<AssociationCouponEventResponse> items) {
}
