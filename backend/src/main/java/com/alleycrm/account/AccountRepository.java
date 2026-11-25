package com.alleycrm.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByLoginId(String loginId);

    boolean existsByStoreId(long storeId);

    boolean existsByLoginId(String loginId);
}
