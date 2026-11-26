package com.alleycrm.recommend.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 추천 후보 걸러내기(FR-CRS-01, 05-policy 「다음 방문 추천」 1단계). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CandidateServiceTest {

    /** 2026-12-04(금) 19:00 Asia/Seoul = 10:00 UTC. 같은 날은 12-03 15:00 UTC ~ 12-04 15:00 UTC. */
    private static final Instant BASE_TIME = Instant.parse("2026-12-04T10:00:00Z");

    @Autowired
    CandidateService service;

    @Autowired
    JdbcTemplate jdbc;

    long region;
    long base;
    long cafe;
    long pub;
    long dessert;

    @BeforeEach
    void seed() {
        region = insert("INSERT INTO region (name) VALUES ('후보 권역')");
        base = store("기준", "cafe");
        cafe = store("같은 업종", "cafe");
        pub = store("주점", "pub");
        dessert = store("디저트", "dessert");
    }

    @Test
    void 기준_가게와_예약을_받지_못하는_가게를_빼고_중복_없이_준다_FR_CRS_01_1() {
        long paused = store("휴업", "restaurant");
        jdbc.update("UPDATE store SET business_status = 'paused' WHERE id = ?", paused);
        long closed = store("운영 중단", "restaurant");
        jdbc.update("UPDATE store SET business_status = 'closed' WHERE id = ?", closed);
        long notTaking = store("예약 받기 꺼짐", "restaurant");
        jdbc.update("UPDATE store SET reservation_enabled = FALSE WHERE id = ?", notTaking);
        long requested = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", '가입 신청 중', 'pub', 'center', 'requested')");
        long otherRegion = insert("INSERT INTO region (name) VALUES ('다른 권역')");
        insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + otherRegion + ", '다른 권역 가게', 'cafe', 'center', 'approved')");

        List<Long> candidates = service.findCandidates(base, BASE_TIME, CandidateRequester.UNIDENTIFIED);

        assertThat(candidates).containsExactly(cafe, pub, dessert);
        assertThat(candidates).doesNotContain(base, paused, closed, notTaking, requested).doesNotHaveDuplicates();
    }

    @Test
    void 회원이_같은_날_이미_예약한_가게는_빠진다_FR_CRS_01_2() {
        long member = customer("member", null);
        reservation(pub, member, LocalDateTime.of(2026, 12, 4, 3, 0), "requested");

        assertThat(service.findCandidates(base, BASE_TIME, CandidateRequester.member(member)))
                .containsExactly(cafe, dessert);
    }

    @Test
    void 같은_날은_서비스_시간대로_센다() {
        long member = customer("member", null);
        // 12-04 00:00 KST 시작 = 같은 날, 12-05 00:00 KST 시작 = 다음 날, 12-03 23:59 KST = 전날
        reservation(pub, member, LocalDateTime.of(2026, 12, 3, 15, 0), "confirmed");
        reservation(cafe, member, LocalDateTime.of(2026, 12, 4, 15, 0), "confirmed");
        reservation(dessert, member, LocalDateTime.of(2026, 12, 3, 14, 59), "confirmed");

        assertThat(service.findCandidates(base, BASE_TIME, CandidateRequester.member(member)))
                .containsExactly(cafe, dessert);
    }

    @Test
    void 취소된_예약은_세지_않고_방문한_예약은_센다() {
        long member = customer("member", null);
        reservation(pub, member, LocalDateTime.of(2026, 12, 4, 3, 0), "canceled");
        reservation(dessert, member, LocalDateTime.of(2026, 12, 4, 2, 0), "visited");

        assertThat(service.findCandidates(base, BASE_TIME, CandidateRequester.member(member)))
                .containsExactly(cafe, pub);
    }

    @Test
    void 다른_손님의_예약은_후보를_바꾸지_않는다() {
        long member = customer("member", null);
        long someoneElse = customer("member", null);
        reservation(pub, someoneElse, LocalDateTime.of(2026, 12, 4, 3, 0), "requested");

        assertThat(service.findCandidates(base, BASE_TIME, CandidateRequester.member(member)))
                .containsExactly(cafe, pub, dessert);
    }

    @Test
    void 비회원은_같은_전화번호의_예약으로_빼고_파기된_손님은_세지_않는다() {
        String phone = "010" + (System.nanoTime() % 100_000_000L);
        long guest = customer("guest", phone);
        reservation(pub, guest, LocalDateTime.of(2026, 12, 4, 3, 0), "requested");
        long purged = customer("guest", phone);
        jdbc.update("UPDATE customer SET purged_at = UTC_TIMESTAMP(6) WHERE id = ?", purged);
        reservation(dessert, purged, LocalDateTime.of(2026, 12, 4, 3, 0), "requested");

        assertThat(service.findCandidates(base, BASE_TIME, CandidateRequester.guest(phone)))
                .containsExactly(cafe, dessert);
    }

    @Test
    void 식별되지_않으면_같은_날_예약으로_빼지_않는다() {
        long member = customer("member", null);
        reservation(pub, member, LocalDateTime.of(2026, 12, 4, 3, 0), "requested");

        assertThat(service.findCandidates(base, BASE_TIME, CandidateRequester.UNIDENTIFIED))
                .containsExactly(cafe, pub, dessert);
    }

    @Test
    void 같은_업종과_빈_슬롯이_없는_가게와_쿠폰_행사는_후보를_바꾸지_않는다_FR_CRS_01_3() {
        jdbc.update("UPDATE store SET capacity_per_slot = 1 WHERE id = ?", dessert);
        long member = customer("member", null);
        long other = customer("member", null);
        reservation(dessert, other, LocalDateTime.of(2026, 12, 4, 10, 0), "confirmed");
        insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + pub + ", 'fixed_discount', 3000, 7, 10, 0, UTC_TIMESTAMP(6))");

        assertThat(service.findCandidates(base, BASE_TIME, CandidateRequester.member(member)))
                .contains(cafe, pub, dessert);
    }

    @Test
    void 기준_가게가_없으면_빈_목록() {
        assertThat(service.findCandidates(999_999_999L, BASE_TIME, CandidateRequester.UNIDENTIFIED)).isEmpty();
    }

    @Test
    void 비회원_식별에는_전화번호가_필요하다() {
        assertThatThrownBy(() -> CandidateRequester.guest(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    private long store(String name, String category) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", '" + name + "', '" + category + "', 'center', 'approved')");
    }

    private long customer(String type, String phone) {
        jdbc.update("INSERT INTO customer (type, name, phone) VALUES (?, ?, ?)",
                type, "guest".equals(type) ? "손님" : null, phone);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 시작 시각은 UTC 값이다. */
    private void reservation(long storeId, long customerId, LocalDateTime startAtUtc, String status) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)
                VALUES (?, ?, ?, ?, 2, ?, ?)
                """, "R" + UUID.randomUUID().toString().substring(0, 12), storeId, customerId, startAtUtc, status,
                startAtUtc.minusDays(1));
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
