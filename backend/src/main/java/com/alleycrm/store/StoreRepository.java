package com.alleycrm.store;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface StoreRepository extends JpaRepository<Store, Long> {

    Page<Store> findByRegionIdAndJoinStatusOrderByIdAsc(long regionId, String joinStatus, Pageable pageable);
}
