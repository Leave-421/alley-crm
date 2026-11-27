package com.alleycrm.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.coupon.AssociationCouponOpenedEvent;
import com.alleycrm.coupon.CouponStatusChangedEvent;
import com.alleycrm.recommend.NextStoreRecommendedEvent;
import com.alleycrm.reservation.ReservationStateService;
import com.alleycrm.reservation.ReservationStatusChangedEvent;
import com.alleycrm.store.StoreJoinDecidedEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** C14 손님 알림 종류 생성 지점(FR-NTF-03 #1 ~ #3), 가게 가입 결과, 상인회 쿠폰 행사 알림(FR-CPN-13). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationDeliveryTest {

    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    AccountService accountService;
    @Autowired
    ReservationStateService states;
    @Autowired
    ReservationReminderJob reminderJob;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ObjectMapper json;

    long regionId;
    long storeId;
    long memberAccountId;
    long memberCustomerId;
    long guestCustomerId;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('알림 생성 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, '알림 생성 가게', 'cafe', 'center', 'approved', '10:00', '22:00', 5)
                """, regionId);
        storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        memberAccountId = accountService.signupCustomer("ntfmember" + System.nanoTime(), "password1", "회원", null, null).id();
        memberCustomerId = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, memberAccountId);
        jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '비회원', '01055556666')");
        guestCustomerId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM reservation WHERE store_id = ? AND course_root_id IS NOT NULL", storeId);
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM customer WHERE id = ?", guestCustomerId);
        jdbc.update("DELETE FROM customer WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'ntf%')");
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'ntf%'");
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_NTF_03_1_확정되면_회원_손님_계정에_확정_알림() {
        long id = reservation(memberCustomerId, "requested", NOW.plus(Duration.ofDays(1)), null);

        states.confirm(storeId, id);

        assertThat(notices("account", memberAccountId))
                .containsExactly(Map.of("type", "reservation_confirmed", "payload", Map.of("reservationId", String.valueOf(id))));
    }

    @Test
    void FR_NTF_03_2_기준_가게_예약이_거절되면_비회원_예약에_거절_알림과_그대로인_다음_코스_예약() {
        long root = reservation(guestCustomerId, "requested", NOW.plus(Duration.ofDays(1)), null);
        long next = reservation(guestCustomerId, "confirmed", NOW.plus(Duration.ofDays(1)).plus(Duration.ofHours(2)), root);
        reservation(guestCustomerId, "canceled", NOW.plus(Duration.ofDays(1)).plus(Duration.ofHours(3)), root);

        states.reject(storeId, root);

        assertThat(notices("guest_reservation", root)).containsExactly(Map.of("type", "reservation_rejected",
                "payload", Map.of("reservationId", String.valueOf(root), "nextCourseReservationIds", List.of(String.valueOf(next)))));
    }

    @Test
    void FR_NTF_03_1_자동_취소는_손님에게_손님_스스로_취소는_사장님에게만_취소_알림() {
        long ownerId = accountService.createOwner(storeId, "ntfowner" + System.nanoTime(), "password1").id();
        long autoCanceled = reservation(memberCustomerId, "confirmed", NOW.minus(Duration.ofDays(3)), null);
        long selfCanceled = reservation(memberCustomerId, "requested", NOW.plus(Duration.ofDays(3)), null);

        states.autoCancelDue();
        states.cancelByCustomer(memberCustomerId, selfCanceled);

        assertThat(notices("account", memberAccountId))
                .containsExactly(Map.of("type", "reservation_canceled", "payload", Map.of("reservationId", String.valueOf(autoCanceled))));
        // FR-NTF-01 #2: 손님 취소는 사장님 알림함에
        assertThat(notices("account", ownerId))
                .containsExactly(Map.of("type", "reservation_canceled", "payload", Map.of("reservationId", String.valueOf(selfCanceled))));
    }

    @Test
    void 예약_변경_요청은_사장님에게_승인_거절은_손님에게_확정_알림과_구분된다() {
        long ownerId = accountService.createOwner(storeId, "ntfowner" + System.nanoTime(), "password1").id();
        long requested = reservation(memberCustomerId, "requested", NOW.plus(Duration.ofDays(2)), null);
        long approved = reservation(memberCustomerId, "confirmed", NOW.plus(Duration.ofDays(2)), null);
        long rejected = reservation(guestCustomerId, "confirmed", NOW.plus(Duration.ofDays(2)), null);

        // D8 ReservationStateService가 내보내는 값 그대로(요청: 확정 → 신청, 승인 · 거절: 신청 → 확정)
        tx.executeWithoutResult(s -> {
            events.publishEvent(new ReservationStatusChangedEvent(requested, storeId, memberCustomerId, "confirmed", "requested",
                    null, false, ReservationStatusChangedEvent.CHANGE_REQUESTED));
            events.publishEvent(new ReservationStatusChangedEvent(approved, storeId, memberCustomerId, "requested", "confirmed",
                    null, false, ReservationStatusChangedEvent.CHANGE_APPROVED));
            events.publishEvent(new ReservationStatusChangedEvent(rejected, storeId, guestCustomerId, "requested", "confirmed",
                    null, true, ReservationStatusChangedEvent.CHANGE_REJECTED));
        });

        assertThat(notices("account", ownerId))
                .containsExactly(Map.of("type", "change_requested", "payload", Map.of("reservationId", String.valueOf(requested))));
        assertThat(notices("account", memberAccountId))
                .containsExactly(Map.of("type", "change_approved", "payload", Map.of("reservationId", String.valueOf(approved))));
        assertThat(notices("guest_reservation", rejected))
                .containsExactly(Map.of("type", "change_rejected", "payload", Map.of("reservationId", String.valueOf(rejected))));
    }

    @Test
    void FR_NTF_03_4_5_쿠폰_사용_완료_만료는_회원_계정에_비회원과_롤백은_없음() {
        tx.executeWithoutResult(s -> {
            events.publishEvent(new CouponStatusChangedEvent(11L, memberCustomerId, "used"));
            events.publishEvent(new CouponStatusChangedEvent(12L, memberCustomerId, "expired"));
            events.publishEvent(new CouponStatusChangedEvent(13L, guestCustomerId, "used"));
        });
        tx.executeWithoutResult(s -> {
            events.publishEvent(new CouponStatusChangedEvent(14L, memberCustomerId, "used"));
            s.setRollbackOnly();
        });

        assertThat(notices("account", memberAccountId)).containsExactly(
                Map.of("type", "coupon_status", "payload", Map.of("couponId", "11", "status", "used")),
                Map.of("type", "coupon_status", "payload", Map.of("couponId", "12", "status", "expired")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE type = 'coupon_status'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void FR_CRS_02_다음_가게_추천이_있으면_회원_계정과_비회원_예약에_알림_롤백되면_없음() {
        long memberReservation = reservation(memberCustomerId, "requested", NOW.plus(Duration.ofDays(1)), null);
        long guestReservation = reservation(guestCustomerId, "requested", NOW.plus(Duration.ofDays(1)), null);
        long rolledBack = reservation(guestCustomerId, "requested", NOW.plus(Duration.ofDays(2)), null);

        tx.executeWithoutResult(s -> {
            events.publishEvent(new NextStoreRecommendedEvent(memberReservation, storeId, memberCustomerId));
            events.publishEvent(new NextStoreRecommendedEvent(guestReservation, storeId, guestCustomerId));
        });
        tx.executeWithoutResult(s -> {
            events.publishEvent(new NextStoreRecommendedEvent(rolledBack, storeId, guestCustomerId));
            s.setRollbackOnly();
        });

        assertThat(notices("account", memberAccountId)).containsExactly(Map.of("type", "next_store_recommended",
                "payload", Map.of("reservationId", String.valueOf(memberReservation), "baseStoreId", String.valueOf(storeId))));
        assertThat(notices("guest_reservation", guestReservation)).containsExactly(Map.of("type", "next_store_recommended",
                "payload", Map.of("reservationId", String.valueOf(guestReservation), "baseStoreId", String.valueOf(storeId))));
        assertThat(notices("guest_reservation", rolledBack)).isEmpty();
    }

    @Test
    void 상태_변경이_롤백되면_알림이_없다() {
        long id = reservation(memberCustomerId, "requested", NOW.plus(Duration.ofDays(1)), null);

        tx.executeWithoutResult(s -> {
            states.confirm(storeId, id);
            s.setRollbackOnly();
        });

        assertThat(notices("account", memberAccountId)).isEmpty();
    }

    @Test
    void FR_NTF_03_3_방문_30분_전_안의_확정_예약에_리마인드를_한_번만() {
        long member = reservation(memberCustomerId, "confirmed", NOW.plus(Duration.ofMinutes(30)), null);
        long guest = reservation(guestCustomerId, "confirmed", NOW.plus(Duration.ofMinutes(10)), null);
        long later = reservation(memberCustomerId, "confirmed", NOW.plus(Duration.ofMinutes(31)), null);
        reservation(guestCustomerId, "requested", NOW.plus(Duration.ofMinutes(20)), null);

        reminderJob.run();
        reminderJob.run();

        assertThat(notices("account", memberAccountId))
                .containsExactly(Map.of("type", "reminder", "payload", Map.of("reservationId", String.valueOf(member))));
        assertThat(notices("guest_reservation", guest))
                .containsExactly(Map.of("type", "reminder", "payload", Map.of("reservationId", String.valueOf(guest))));

        clock.setTo(NOW.plus(Duration.ofMinutes(5)));
        reminderJob.run();
        assertThat(notices("account", memberAccountId)).hasSize(2)
                .contains(Map.of("type", "reminder", "payload", Map.of("reservationId", String.valueOf(later))));
    }

    @Test
    void 가게_가입이_정해지면_그_가게_사장님에게_가입_결과_알림() {
        long ownerId = accountService.createOwner(storeId, "ntfowner" + System.nanoTime(), "password1").id();

        tx.executeWithoutResult(s -> events.publishEvent(new StoreJoinDecidedEvent(storeId, "approved")));

        assertThat(notices("account", ownerId)).containsExactly(Map.of("type", "store_join_result",
                "payload", Map.of("storeId", String.valueOf(storeId), "joinStatus", "approved")));
    }

    @Test
    void FR_CPN_13_상인회_쿠폰_행사는_회원_계정_전부에게_사장님과_비회원에게는_없음() {
        long ownerId = accountService.createOwner(storeId, "ntfowner" + System.nanoTime(), "password1").id();

        tx.executeWithoutResult(s -> events.publishEvent(new AssociationCouponOpenedEvent(5L, regionId)));

        assertThat(notices("account", memberAccountId)).containsExactly(Map.of("type", "association_coupon_opened",
                "payload", Map.of("associationCouponEventId", "5")));
        assertThat(notices("account", ownerId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE recipient_type = 'guest_reservation'", Integer.class))
                .isZero();
        Integer customers = jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE role = 'customer'", Integer.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE type = 'association_coupon_opened'", Integer.class))
                .isEqualTo(customers);
    }

    private long reservation(long customerId, String status, Instant startAt, Long courseRootId) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, confirmed_at, course_root_id)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?, ?)
                """, "N" + System.nanoTime() % 1_000_000_000_000L, storeId, customerId, utc(startAt), status,
                "canceled".equals(status) ? "customer_canceled" : null, utc(NOW.minus(Duration.ofDays(5))),
                "confirmed".equals(status) ? utc(NOW.minus(Duration.ofDays(4))) : null, courseRootId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 받는 쪽의 알림 type · payload, 만든 순서대로 */
    private List<Map<String, Object>> notices(String recipientType, long recipientId) {
        return jdbc.query("SELECT type, payload FROM notification WHERE recipient_type = ? AND recipient_id = ? ORDER BY id",
                (rs, i) -> Map.of("type", rs.getString("type"), "payload", json.readValue(rs.getString("payload"), Map.class)),
                recipientType, recipientId);
    }

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
