package com.alleycrm.reservation.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** D5 예약 운영 설정 · 슬롯 계산(FR-RSV-05 #1 ~ #6). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SlotSettingsTest {

    /** 2026-11-27(금) 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    ObjectMapper json;

    long storeId;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('권역')");
        long region = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        // 기본값(기간 30일 · 슬롯 30분 · 동시 1건) 그대로, 영업 10:00 ~ 22:00, 월요일 휴무
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, closed_days)
                VALUES (?, '가게', 'cafe', 'center', 'approved', '10:00', '22:00', 'mon')
                """, region);
        storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('owner1', 'x', 'owner', ?)", storeId);
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ---- 설정 저장

    @Test
    void FR_RSV_05_1_취소_마감을_24시간보다_이르게_잡으면_거절() throws Exception {
        saveSettings(Map.of("cancelDeadlineHours", 30))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void FR_RSV_05_6_예약_가능_기간을_61일로_잡으면_거절() throws Exception {
        saveSettings(Map.of("bookingWindowDays", 61)).andExpect(status().isBadRequest());
        saveSettings(Map.of("bookingWindowDays", 60)).andExpect(status().isOk());
    }

    @Test
    void 설정을_저장하고_읽는다_환불_비율을_비우면_권역_기본값() throws Exception {
        saveSettings(Map.of("slotMinutes", 60, "capacityPerSlot", 3, "maxPartySize", 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotMinutes").value(60))
                .andExpect(jsonPath("$.capacityPerSlot").value(3))
                .andExpect(jsonPath("$.maxPartySize").value(4))
                .andExpect(jsonPath("$.lateCancelRefundRate").doesNotExist())
                .andExpect(jsonPath("$.effectiveLateCancelRefundRate").value(0.5));
        mvc.perform(get("/api/v1/owner/reservation-settings").with(user("owner1")))
                .andExpect(jsonPath("$.slotMinutes").value(60));
    }

    // ---- 신청 검사

    @Test
    void FR_RSV_05_2_동시_2건이면_같은_슬롯_세_번째는_거절() throws Exception {
        jdbc.update("UPDATE store SET capacity_per_slot = 2 WHERE id = ?", storeId);
        String slot = "2026-11-28T09:00:00Z"; // 토 18:00
        request(slot, 2, "01000000001").andExpect(status().isCreated());
        request(slot, 2, "01000000002").andExpect(status().isCreated());
        request(slot, 2, "01000000003")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("slot_unavailable"));
    }

    @Test
    void FR_RSV_05_3_한_팀_최대_인원을_넘으면_거절() throws Exception {
        jdbc.update("UPDATE store SET max_party_size = 4 WHERE id = ?", storeId);
        request("2026-11-28T09:00:00Z", 5, "01000000001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        request("2026-11-28T09:00:00Z", 4, "01000000001").andExpect(status().isCreated());
    }

    @Test
    void FR_RSV_05_4_5_기본_30일_오늘_더하기_30일은_되고_31일은_거절() throws Exception {
        // 오늘 11-27 + 30일 = 12-27(일), 31일 = 12-28(월 휴무라 화요일 12-29도 대신 확인할 수 없음 → 기간만 보는 날짜 선택)
        request("2026-12-27T03:00:00Z", 2, "01000000001").andExpect(status().isCreated()); // 12:00
        jdbc.update("UPDATE store SET closed_days = '' WHERE id = ?", storeId);
        request("2026-12-28T03:00:00Z", 2, "01000000002")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("slot_unavailable"));
    }

    @Test
    void 영업시간_밖_휴무일_슬롯_단위가_안_맞으면_거절() throws Exception {
        request("2026-11-28T00:30:00Z", 2, "01000000001").andExpect(status().isConflict()); // 09:30 영업 전
        request("2026-11-28T12:40:00Z", 2, "01000000002").andExpect(status().isConflict()); // 21:40 마지막 슬롯 뒤
        request("2026-11-28T09:15:00Z", 2, "01000000003").andExpect(status().isConflict()); // 18:15 슬롯 단위 아님
        request("2026-11-30T03:00:00Z", 2, "01000000004").andExpect(status().isConflict()); // 월 휴무
        request("2026-11-28T12:30:00Z", 2, "01000000005").andExpect(status().isCreated());  // 21:30 마지막 슬롯
    }

    @Test
    void 자정을_넘겨_영업하면_새벽_슬롯도_전날_영업일() {
        StoreSnapshot pub = new StoreSnapshot(1, 1, "approved", "open", true,
                LocalTime.of(18, 0), LocalTime.of(2, 0), "sat", 60, 1, 30, 0, null);
        // 금 영업일(11-27): 18:00 ~ 01:00 시작 슬롯 8개
        List<Instant> fri = SlotRules.slotsOf(pub, java.time.LocalDate.of(2026, 11, 27));
        assertThat(fri).hasSize(8);
        assertThat(fri.get(7)).isEqualTo(Instant.parse("2026-11-27T16:00:00Z")); // 11-28 01:00 서울
        // 토요일 새벽 01:00은 금요일 영업일 슬롯이라 토 휴무여도 가능
        assertThat(SlotRules.isSlotStart(pub, Instant.parse("2026-11-27T16:00:00Z"))).isTrue();
        // 토요일 18:00은 휴무
        assertThat(SlotRules.isSlotStart(pub, Instant.parse("2026-11-28T09:00:00Z"))).isFalse();
    }

    // ---- 예약 가능 시간 조회

    @Test
    void 예약_가능_시간은_로그인_없이_보고_찬_슬롯은_불가() throws Exception {
        request("2026-11-28T01:00:00Z", 2, "01000000001").andExpect(status().isCreated()); // 토 10:00

        mvc.perform(get("/api/v1/stores/" + storeId + "/available-slots").param("date", "2026-11-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingReservations").value(true))
                .andExpect(jsonPath("$.slots.length()").value(24)) // 10:00 ~ 21:30
                .andExpect(jsonPath("$.slots[0].startAt").value("2026-11-28T01:00:00Z"))
                .andExpect(jsonPath("$.slots[0].available").value(false))
                .andExpect(jsonPath("$.slots[1].available").value(true));
        mvc.perform(get("/api/v1/stores/" + storeId + "/available-slots").param("date", "2026-11-30"))
                .andExpect(jsonPath("$.slots.length()").value(0)); // 월 휴무
    }

    private ResultActions saveSettings(Map<String, Object> overrides) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("slotMinutes", 30);
        b.put("capacityPerSlot", 1);
        b.put("maxPartySize", null);
        b.put("bookingWindowDays", 30);
        b.put("cancelDeadlineHours", 24);
        b.put("lateCancelRefundRate", null);
        b.put("depositPerPerson", 0);
        b.putAll(overrides);
        return mvc.perform(put("/api/v1/owner/reservation-settings").with(TestCsrf.csrf(mvc)).with(user("owner1"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));
    }

    private ResultActions request(String startAt, int party, String phone) throws Exception {
        Map<String, Object> b = Map.of("storeId", String.valueOf(storeId), "startAt", startAt, "partySize", party,
                "shareConsent", true, "guest", Map.of("name", "손님", "phone", phone));
        return mvc.perform(post("/api/v1/reservations").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));
    }
}
