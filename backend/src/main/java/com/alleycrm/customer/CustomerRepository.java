package com.alleycrm.customer;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByAccountId(long accountId);

    Optional<Customer> findFirstByTypeAndPhoneAndPurgedAtIsNullOrderByIdAsc(String type, String phone);

    List<Customer> findByTypeAndPhoneAndPurgedAtIsNull(String type, String phone);
}
