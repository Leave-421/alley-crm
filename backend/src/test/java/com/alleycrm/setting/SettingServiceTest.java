package com.alleycrm.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SettingServiceTest {

    @Autowired
    SettingService settings;

    @Autowired
    JdbcTemplate jdbc;

    long regionId;

    @BeforeEach
    void region() {
        jdbc.update("INSERT INTO region (name) VALUES ('테스트 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void 바꾼_값이_없으면_기본값() {
        assertThat(settings.getDecimal(regionId, SettingKey.REFERRAL_FEE_RATE)).isEqualByComparingTo("0.04");
        assertThat(settings.getInt(regionId, SettingKey.REVISIT_DAYS)).isEqualTo(60);
    }

    @Test
    void NFR_04_수수료율을_4에서_5퍼센트로_바꾸면_코드_수정_없이_5퍼센트를_읽는다() {
        BigDecimal before = settings.getDecimal(regionId, SettingKey.REFERRAL_FEE_RATE);

        settings.set(regionId, SettingKey.REFERRAL_FEE_RATE, "0.05");

        assertThat(before).isEqualByComparingTo("0.04");
        assertThat(settings.getDecimal(regionId, SettingKey.REFERRAL_FEE_RATE)).isEqualByComparingTo("0.05");
        // 이미 생긴 수수료가 4% 그대로인 것은 수수료가 생길 때 비율을 저장하는 D15에서 확인한다.
    }

    @Test
    void 같은_키를_다시_바꾸면_덮어쓴다() {
        settings.set(regionId, SettingKey.REVISIT_DAYS, "90");
        settings.set(regionId, SettingKey.REVISIT_DAYS, "45");

        assertThat(settings.getInt(regionId, SettingKey.REVISIT_DAYS)).isEqualTo(45);
    }

    @Test
    void 숫자가_아닌_값은_거절() {
        assertThatThrownBy(() -> settings.set(regionId, SettingKey.REVISIT_DAYS, "sixty"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void 가게당_사장님_계정은_하나() {
        jdbc.update("INSERT INTO store (region_id, name, category, alley) VALUES (?, '가게', 'cafe', 'center')", regionId);
        long storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('owner1', 'x', 'owner', ?)", storeId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('owner2', 'x', 'owner', ?)", storeId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 취소_마감은_24시간을_넘을_수_없다() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO store (region_id, name, category, alley, cancel_deadline_hours) VALUES (?, '가게', 'cafe', 'center', 48)",
                regionId))
                // MySQL CHECK 위반(3819)은 Spring이 DataIntegrityViolationException으로 바꾸지 않는다
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_store_cancel_deadline");
    }
}
