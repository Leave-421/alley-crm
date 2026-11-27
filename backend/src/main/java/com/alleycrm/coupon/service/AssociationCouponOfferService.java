package com.alleycrm.coupon.service;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.AssociationCouponEvent;
import com.alleycrm.coupon.domain.AssociationCouponOfferStatus;
import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.EligibleStoreScope;
import com.alleycrm.coupon.dto.AssociationCouponOfferResponse;
import com.alleycrm.coupon.dto.AssociationCouponOfferResponse.OfferStore;
import com.alleycrm.coupon.dto.CouponResponse;
import com.alleycrm.coupon.repository.AssociationCouponEventRepository;
import com.alleycrm.coupon.repository.AssociationCouponReader;
import com.alleycrm.coupon.repository.CouponRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원의 상인회 쿠폰 받기(E19 #162, CONTRACTS_API 3.9, FR-CPN-13). 행사 카드 조회와 선착순 받기.
 * 같은 행사에서 한 사람당 1장이고, 받은 수는 조건부 UPDATE로 총수량을 넘지 않는다(NFR-09).
 *
 * <p>{@code customerId}는 호출하는 쪽이 로그인한 회원 계정의 손님으로 정해서 넘긴다.
 */
@Service
public class AssociationCouponOfferService {

    /** 같은 행사 · 같은 회원 한 장(D24). */
    static final String ONE_PER_MEMBER_CONSTRAINT = "uk_coupon_association_member";

    private final AssociationCouponEventRepository events;
    private final CouponRepository coupons;
    private final AssociationCouponReader reader;
    private final Clock clock;

    public AssociationCouponOfferService(AssociationCouponEventRepository events, CouponRepository coupons,
            AssociationCouponReader reader, Clock clock) {
        this.events = events;
        this.coupons = coupons;
        this.reader = reader;
        this.clock = clock;
    }

    /** 행사 카드. 없는 행사는 404. 마감 · 중단 뒤에도 카드는 보인다(FR-CPN-13 #1). */
    @Transactional(readOnly = true)
    public AssociationCouponOfferResponse offer(long eventId, long customerId) {
        AssociationCouponEvent event = events.findById(eventId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Optional<Coupon> mine = coupons.findByAssociationEventIdAndCustomerId(eventId, customerId);
        return AssociationCouponOfferResponse.of(event, stores(event),
                AssociationCouponOfferStatus.of(event, mine.isPresent(), now()), mine.map(Coupon::getId).orElse(null));
    }

    /**
     * 선착순 받기. 없는 행사 404, 이미 받음 · 다 나감 · 중단 · 받는 기간 밖은 409이고 아무것도 남기지 않는다.
     * 받은 수 조건부 UPDATE를 먼저 해 행사 행을 잠그고, 같은 회원 동시 요청은 유일 제약으로 한 장만 남긴다.
     * 가게 쿠폰 다운로드(E12)와 같이 READ COMMITTED로 돌려 갭 잠금을 잡지 않는다(기본 REPEATABLE READ에서는 동시 받기
     * 테스트가 잠금 획득 실패로 깨졌다). 같은 행사 안의 순서는 행사 행 잠금이 지킨다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponResponse claim(long eventId, long customerId) {
        if (!events.existsById(eventId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        if (coupons.findByAssociationEventIdAndCustomerId(eventId, customerId).isPresent()) {
            throw alreadyReceived();
        }
        Instant now = now();
        if (events.issueOne(eventId, now) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "받을 수 없는 상인회 쿠폰입니다(마감 · 중단 · 기간 밖).");
        }
        AssociationCouponEvent event = events.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("상인회 쿠폰 행사를 다시 읽지 못했습니다: " + eventId));
        try {
            Coupon saved = coupons.saveAndFlush(Coupon.claimAssociation(customerId, event, now));
            return CouponResponse.of(saved, event);
        } catch (DataIntegrityViolationException e) {
            // 같은 회원이 동시에 두 번 받음: 먼저 커밋한 쪽만 남고, 이 트랜잭션은 받은 수 증가까지 되돌린다.
            if (violates(e, ONE_PER_MEMBER_CONSTRAINT)) {
                throw alreadyReceived();
            }
            throw e;
        }
    }

    private List<OfferStore> stores(AssociationCouponEvent event) {
        if (event.getEligibleStoreScope() != EligibleStoreScope.SELECTED) {
            return List.of();
        }
        return reader.storeNames(event.getStoreIds()).entrySet().stream()
                .map(e -> new OfferStore(String.valueOf(e.getKey()), e.getValue()))
                .toList();
    }

    /** DB 칸이 DATETIME(6)이라 마이크로초까지만 쓴다. 값은 UTC. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException alreadyReceived() {
        return new ApiException(ErrorCode.CONFLICT, "이 행사 쿠폰은 이미 받았습니다(한 사람당 1장).");
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
