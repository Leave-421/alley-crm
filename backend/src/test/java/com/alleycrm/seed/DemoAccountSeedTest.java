package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.account.AccountService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 시연용 고정 계정 시드(FR-AUTH-05, #141). 비밀번호는 테스트 전용 값을 환경 변수 이름으로 준다. Docker 필요. */
@SpringBootTest(properties = {
        "seed.close-after-run=false",
        "DEMO_CUSTOMER_PASSWORD=test-customer-pw",
        "DEMO_OWNER_PASSWORD=test-owner-pw",
        "DEMO_ASSOCIATION_PASSWORD=test-assoc-pw"})
@ActiveProfiles("seed")
@Import(TestcontainersConfiguration.class)
class DemoAccountSeedTest {

    @Autowired
    SeedLoader loader;

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcTemplate jdbc;

    private final SeedRegionData data = SeedDataLoader.loadRegionStores();

    @Test
    void 고정_아이디_3개로_로그인된다() {
        SeedLoader.Result result = loader.resetAndLoad(data);

        assertThat(result.demoAccounts()).containsExactly("democustomer", "demoowner", "demoassoc");
        AccountPrincipal customer = accounts.authenticate("democustomer", "test-customer-pw");
        AccountPrincipal owner = accounts.authenticate("demoowner", "test-owner-pw");
        AccountPrincipal association = accounts.authenticate("demoassoc", "test-assoc-pw");

        assertThat(customer.role()).isEqualTo("customer");
        assertThat(owner.role()).isEqualTo("owner");
        assertThat(owner.storeId()).isEqualTo(result.regionStores().storeIdsByCode().get("S01"));
        assertThat(association.role()).isEqualTo("association");
        assertThat(association.regionId()).isEqualTo(result.regionStores().regionId());
    }

    @Test
    void 손님은_성별_연령대가_있고_사장님_가게는_가입_승인_상태다() {
        loader.resetAndLoad(data);

        Map<String, Object> member = jdbc.queryForMap("""
                SELECT c.type, c.gender, c.age_group FROM customer c JOIN account a ON a.id = c.account_id
                WHERE a.login_id = 'democustomer'""");
        assertThat(member).containsEntry("type", "member").containsEntry("gender", "female").containsEntry("age_group", "age_30s");
        assertThat(jdbc.queryForObject("""
                SELECT s.join_status FROM store s JOIN account a ON a.store_id = s.id WHERE a.login_id = 'demoowner'""",
                String.class)).isEqualTo("approved");
    }

    @Test
    void 비밀번호는_해시로만_저장된다() {
        loader.resetAndLoad(data);

        assertThat(jdbc.queryForList("SELECT password_hash FROM account", String.class))
                .hasSize(3)
                .noneMatch(hash -> hash.contains("test-customer-pw") || hash.contains("test-owner-pw") || hash.contains("test-assoc-pw"));
    }

    @Test
    void 다시_돌려도_같은_아이디로_다시_생긴다() {
        loader.resetAndLoad(data);
        loader.resetAndLoad(data);

        assertThat(jdbc.queryForList("SELECT login_id FROM account ORDER BY login_id", String.class))
                .containsExactly("demoassoc", "democustomer", "demoowner");
        assertThat(accounts.authenticate("demoowner", "test-owner-pw").role()).isEqualTo("owner");
    }

    @Test
    void 비밀번호_환경_변수가_비면_그_계정은_만들지_않는다() {
        RegionStoreSeeder.Result stores = loader.resetAndLoad(data).regionStores();
        jdbc.update("DELETE FROM reservation"); // 가상 데이터 1(E16)의 예약이 손님을 가리킨다
        jdbc.update("DELETE FROM customer");
        jdbc.update("DELETE FROM account");

        DemoAccountSeeder blank = new DemoAccountSeeder(accounts, "", " ", "test-assoc-pw");

        assertThat(blank.insert(stores)).containsExactly("demoassoc");
        assertThat(jdbc.queryForList("SELECT login_id FROM account", String.class)).containsExactly("demoassoc");
    }
}
