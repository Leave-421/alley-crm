package com.alleycrm.seed;

import com.alleycrm.account.AccountService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * ③ 시연용 고정 계정 3개(FR-AUTH-05, #141). 아이디는 고정, 비밀번호는 환경 변수(루트 .env)로만 받는다.
 * 계정은 C의 {@link AccountService}로 만든다(해시 · 아이디 중복 검사 그대로). 비밀번호가 비어 있으면 그 계정은 건너뛴다.
 */
@Component
@Profile("seed")
class DemoAccountSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountSeeder.class);

    static final String CUSTOMER_LOGIN_ID = "democustomer";
    static final String OWNER_LOGIN_ID = "demoowner";
    static final String ASSOCIATION_LOGIN_ID = "demoassoc";
    /** 사장님 계정의 가게. shortcut: B 확인 전 잠정값(카페 S01), 확정되면 이 값만 바꾼다. */
    static final String OWNER_STORE_CODE = "S01";

    private final AccountService accounts;
    private final String customerPassword;
    private final String ownerPassword;
    private final String associationPassword;

    DemoAccountSeeder(AccountService accounts,
            @Value("${DEMO_CUSTOMER_PASSWORD:}") String customerPassword,
            @Value("${DEMO_OWNER_PASSWORD:}") String ownerPassword,
            @Value("${DEMO_ASSOCIATION_PASSWORD:}") String associationPassword) {
        this.accounts = accounts;
        this.customerPassword = customerPassword;
        this.ownerPassword = ownerPassword;
        this.associationPassword = associationPassword;
    }

    /** 만든 계정의 아이디 목록. */
    List<String> insert(RegionStoreSeeder.Result regionStores) {
        List<String> created = new ArrayList<>();
        if (ready(CUSTOMER_LOGIN_ID, customerPassword, "DEMO_CUSTOMER_PASSWORD")) {
            accounts.signupCustomer(CUSTOMER_LOGIN_ID, customerPassword, "시연 손님", "female", "age_30s");
            created.add(CUSTOMER_LOGIN_ID);
        }
        if (ready(OWNER_LOGIN_ID, ownerPassword, "DEMO_OWNER_PASSWORD")) {
            Long storeId = regionStores.storeIdsByCode().get(OWNER_STORE_CODE);
            if (storeId == null) {
                throw new IllegalStateException("시연 사장님 계정의 가게 " + OWNER_STORE_CODE + "가 시드에 없습니다.");
            }
            accounts.createOwner(storeId, OWNER_LOGIN_ID, ownerPassword); // 시드 가게는 가입 승인 상태로 들어간다
            created.add(OWNER_LOGIN_ID);
        }
        if (ready(ASSOCIATION_LOGIN_ID, associationPassword, "DEMO_ASSOCIATION_PASSWORD")) {
            accounts.createAssociation(regionStores.regionId(), ASSOCIATION_LOGIN_ID, associationPassword);
            created.add(ASSOCIATION_LOGIN_ID);
        }
        return created;
    }

    private static boolean ready(String loginId, String password, String envName) {
        if (password == null || password.isBlank()) {
            log.warn("[seed] {} 계정을 건너뜁니다: 환경 변수 {}가 비어 있습니다.", loginId, envName);
            return false;
        }
        return true;
    }
}
