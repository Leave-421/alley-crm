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

    /** 회원 손님 식별(FR-CUS-02): 계정의 손님. 회원가입이 항상 만들므로 없으면 데이터 오류다. */
    @Transactional(readOnly = true)
    public long identifyMember(long accountId) {
        return customers.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("회원 계정에 손님 정보가 없다: account " + accountId))
                .getId();
    }

    /**
     * 비회원 손님 식별(FR-CUS-02): 파기되지 않은 같은 전화번호의 비회원이 있으면 그 손님, 없으면 새로 만든다.
     * 가게를 넘어 같은 손님으로 방문 기록이 이어진다.
     * shortcut: 같은 새 전화번호로 동시에 신청하면 손님이 둘 생길 수 있다(phone에 유일 제약 없음), 문제가 되면 D에게 제약 · 잠금을 요청한다.
     */
    @Transactional
    public long identifyGuest(String name, String phone, String gender, String ageGroup) {
        return customers.findFirstByTypeAndPhoneAndPurgedAtIsNullOrderByIdAsc(Customer.TYPE_GUEST, phone)
                .map(existing -> {
                    existing.updateGuestInfo(name, gender, ageGroup);
                    return existing;
                })
                .orElseGet(() -> customers.save(Customer.guest(name, phone, gender, ageGroup)))
                .getId();
    }
}
