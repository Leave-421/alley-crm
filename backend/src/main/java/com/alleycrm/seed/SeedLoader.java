package com.alleycrm.seed;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시드 · 시연 데이터를 한 트랜잭션에서 지우고 다시 넣는다. 중간에 실패하면 지운 것까지 모두 롤백된다.
 * 같은 DB에 두 실행기가 동시에 돌지 않도록 MySQL 이름 잠금을 잡는다.
 *
 * <p>넣는 순서: ① 초기화 → ② 권역 · 가게(E1). 이어서 ③ 상인회 · 사장님 계정(C2 · C5 단계), ④ 공공데이터(E2),
 * ⑤ 가상 데이터 1(E16), ⑥ 가상 데이터 2(E17)를 각 이슈에서 이 메서드에 더한다.
 */
@Component
@Profile("seed")
class SeedLoader {

    static final String LOCK_NAME = "alley_crm_seed";

    private final JdbcTemplate jdbc;
    private final SeedTableCleaner cleaner;
    private final RegionStoreSeeder regionStores;

    SeedLoader(JdbcTemplate jdbc, SeedTableCleaner cleaner, RegionStoreSeeder regionStores) {
        this.jdbc = jdbc;
        this.cleaner = cleaner;
        this.regionStores = regionStores;
    }

    record Result(List<String> clearedTables, RegionStoreSeeder.Result regionStores) {
    }

    @Transactional
    public Result resetAndLoad(SeedRegionData regionStoreData) {
        acquireLock();
        try {
            List<String> cleared = cleaner.deleteAll();
            RegionStoreSeeder.Result stores = regionStores.insert(regionStoreData);
            return new Result(cleared, stores);
        } finally {
            releaseLock();
        }
    }

    /** 트랜잭션과 같은 연결에서 잡는다. 이미 다른 연결이 잡고 있으면 기다리지 않고 실패한다. */
    private void acquireLock() {
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, LOCK_NAME);
        if (acquired == null || acquired != 1) {
            throw new IllegalStateException("다른 시드 실행이 같은 DB에서 진행 중입니다. 끝난 뒤 다시 실행하세요.");
        }
    }

    private void releaseLock() {
        jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK_NAME);
    }
}
