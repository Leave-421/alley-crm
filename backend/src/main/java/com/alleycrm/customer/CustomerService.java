package com.alleycrm.customer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository customers;

    CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    /** 회원가입 때 회원 손님 정보를 만든다. 예약에 들어갈 성별 · 연령대의 출처다(FR-AUTH-02). */
    @Transactional
    public long createMember(long accountId, String name, String gender, String ageGroup) {
        return customers.save(Customer.member(accountId, name, gender, ageGroup)).getId();
    }
}
