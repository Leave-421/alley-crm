package com.alleycrm.recommend.recommendation;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.recommend.recommendation.RecommendationService.Requester;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** C11: 상인회가 성별 · 연령대 공유를 끄면 추천이 회원의 성별 · 연령대를 쓰지 않는다 (FR-CUS-06 #1). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SharePolicyRecommendationTest {

    @Autowired
    RecommendationService service;
    @MockitoSpyBean
    RecommendationRepository repository;
    @Autowired
    AccountService accountService;
    @Autowired
    JdbcTemplate jdbc;

    long regionId;
    long storeId;
    long customerId;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO region (name) VALUES ('공유 추천 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, '공유 추천 기준 가게', 'cafe', 'center', 'approved', '10:00', '22:00', 5)
                """, regionId);
        storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long accountId = accountService.signupCustomer("sprec" + System.nanoTime(), "password1", "회원", "female", "age_30s").id();
        customerId = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, accountId);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM share_policy WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM customer WHERE id = ?", customerId);
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'sprec%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_CUS_06_1_성별_연령대_공유를_끄면_추천이_회원_프로필을_읽지_않는다() {
        service.recommend(storeId, 0, new Requester(customerId));
        verify(repository).memberProfile(customerId);

        clearInvocations(repository);
        jdbc.update("INSERT INTO share_policy (region_id, item, enabled) VALUES (?, 'gender_age_group', FALSE)", regionId);
        service.recommend(storeId, 0, new Requester(customerId));
        verify(repository, never()).memberProfile(anyLong());
    }
}
