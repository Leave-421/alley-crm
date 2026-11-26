package com.alleycrm.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByLoginId(String loginId);

    boolean existsByStoreId(long storeId);

    boolean existsByLoginId(String loginId);

    Optional<Account> findByStoreId(long storeId);

    @Query("SELECT a.id FROM Account a WHERE a.role = :role ORDER BY a.id")
    List<Long> findIdsByRole(String role);
}
