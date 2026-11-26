package com.alleycrm.coupon.service;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.domain.Coupon;
import com.alleycrm.coupon.domain.CouponPolicy;
import com.alleycrm.coupon.domain.CouponStatus;
import com.alleycrm.coupon.domain.DownloadStatus;
import com.alleycrm.coupon.dto.CouponOfferResponse;
import com.alleycrm.coupon.dto.CouponResponse;
import com.alleycrm.coupon.repository.CouponLimitRepository;
import com.alleycrm.coupon.repository.CouponLimitRepository.TargetStore;
import com.alleycrm.coupon.repository.CouponPolicyRepository;
import com.alleycrm.coupon.repository.CouponRepository;
import com.alleycrm.customer.CustomerService;
import com.alleycrm.recommend.record.ExplorationRecordService;
import com.alleycrm.recommend.record.ExposureSource;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천 쿠폰 상세 · 다운로드(E12, CONTRACTS_API 3.4). 쿠폰은 회원만, 추천 카드의 '쿠폰 다운로드'로만 받는다(FR-CPN-03).
 * 대상 가게 · 출처는 클라이언트가 보내지 않고 이 요청자의 추천 노출 기록에서 정한다(FR-CPN-10).
 */
@Service
public class CouponOfferService {

    private static final List<CouponStatus> HELD = List.of(CouponStatus.DOWNLOADED, CouponStatus.APPLIED);

    private final CouponPolicyRepository events;
    private final CouponRepository coupons;
    private final CouponLimitRepository limits;
    private final CustomerService customers;
    private final ExplorationRecordService records;
    private final Clock clock;

    public CouponOfferService(CouponPolicyRepository events, CouponRepository coupons, CouponLimitRepository limits,
            CustomerService customers, ExplorationRecordService records, Clock clock) {
        this.events = events;
        this.coupons = coupons;
        this.limits = limits;
        this.customers = customers;
        this.records = records;
        this.clock = clock;
    }

    /**
     * 쿠폰 상세(FR-CPN-02 · 04). {@code memberAccountId}는 로그인한 회원 계정, 로그인하지 않았으면 null.
     * 손님에게 보이지 않는 가게면 404. 기록하지 않는다.
     */
    @Transactional(readOnly = true)
    public CouponOfferResponse offer(long storeId, Long memberAccountId) {
        TargetStore store = limits.findVisibleStore(storeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        CouponPolicy event = events.findByStoreIdAndStoppedAtIsNull(storeId).orElse(null);
        Instant now = now();
        Long customerId = memberAccountId == null ? null : customers.identifyMember(memberAccountId);

        DownloadLimit.Result result;
        if (event == null || !event.isBenefitAvailable() || !store.isOpen()) {
            result = new DownloadLimit.Result(DownloadStatus.UNAVAILABLE, null);
        } else if (customerId == null) {
            result = new DownloadLimit.Result(DownloadStatus.LOGIN_REQUIRED, null);
        } else {
            result = DownloadLimit.check(
                    limits.findPastDownloads(customerId, event.getId(), store.businessStatusChangedAt()), now);
        }
        List<Coupon> mine = customerId == null ? List.of() : heldCoupons(customerId, storeId, now);
        return CouponOfferResponse.of(storeId, event, result.status(), result.nextDownloadableAt(), mine);
    }

    /**
     * 쿠폰 다운로드(FR-CPN-03 · 04 · 10). 이 요청자의 노출이 아니거나 대상 가게가 보이지 않으면 404,
     * 발급 조건이 하나라도 어긋나면 409이고 아무것도 저장하지 않는다.
     *
     * <p>원자 처리(3.4): 발급 수 조건부 UPDATE를 먼저 해서 행사 행을 잠근다. 같은 행사의 다운로드는 여기서 커밋까지
     * 한 줄로 서고, 그 뒤 회원별 조건을 잠그는 읽기로 확인한다. 걸리면 예외로 발급 수까지 되돌린다.
     * READ COMMITTED라 잠그는 읽기가 갭 잠금을 잡지 않는다 — 같은 회원이 다른 행사를 동시에 받을 때 서로의 갭 잠금을
     * 기다리는 교착을 피한다. 같은 행사 안의 순서는 행사 행 잠금이 지킨다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponResponse download(long memberAccountId, long exposureId) {
        long customerId = customers.identifyMember(memberAccountId);
        ExposureSource source = records.findOwnedExposure(exposureId, customerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        TargetStore store = limits.findVisibleStore(source.recommendedStoreId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!store.isOpen()) {
            throw notIssuable();
        }
        CouponPolicy event = events.findByStoreIdAndStoppedAtIsNull(store.id()).orElseThrow(CouponOfferService::notIssuable);
        if (events.issueOne(event.getId()) == 0) {
            throw notIssuable();
        }
        Instant now = now();
        DownloadLimit.Result limit = DownloadLimit.check(
                limits.findPastDownloadsLocked(customerId, event.getId(), store.businessStatusChangedAt()), now);
        if (limit.status() != DownloadStatus.AVAILABLE) {
            throw notIssuable();
        }
        Coupon saved = coupons.save(Coupon.download(customerId, event, source.baseStoreId(), source.exposureId(), now));
        return CouponResponse.of(saved, event);
    }

    /** 이 가게의 내 쿠폰 중 '다운로드됨'(기한 안) · '예약에 적용됨'. 기한이 지난 미적용 쿠폰은 만료 처리 전이어도 뺀다. */
    private List<Coupon> heldCoupons(long customerId, long storeId, Instant now) {
        return coupons.findByCustomerIdAndTargetStoreIdAndStatusInOrderByDownloadedAtDesc(customerId, storeId, HELD)
                .stream()
                .filter(coupon -> coupon.getStatus() == CouponStatus.APPLIED || now.isBefore(coupon.getExpiresAt()))
                .toList();
    }

    private static ApiException notIssuable() {
        return new ApiException(ErrorCode.CONFLICT, "지금은 이 쿠폰을 받을 수 없습니다.");
    }

    /** DB 칸이 DATETIME(6)이라 마이크로초까지만 쓴다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
