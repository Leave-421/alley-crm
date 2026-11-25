package com.alleycrm.account;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.customer.CustomerService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerService customerService;
    private final PasswordEncoder passwordEncoder;
    /** 없는 아이디도 해시 비교를 한 번 해서, 응답 시간으로 아이디 존재 여부가 드러나지 않게 한다. */
    private final String dummyHash;

    AccountService(AccountRepository accounts, CustomerService customerService, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.customerService = customerService;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /** 손님 회원가입: 계정(customer)과 회원 손님 정보(이름 · 성별 · 연령대)를 같이 만든다. */
    @Transactional
    public AccountPrincipal signupCustomer(String loginId, String rawPassword, String name, String gender, String ageGroup) {
        Account account = saveNew(Account.customer(loginId, passwordEncoder.encode(rawPassword)));
        customerService.createMember(account.getId(), name, gender, ageGroup);
        return account.toPrincipal();
    }

    /** 상인회 계정 만들기. 가입 API는 없고 초기 데이터(시드 실행기)만 부른다(FR-AUTH-05). */
    @Transactional
    public AccountPrincipal createAssociation(long regionId, String loginId, String rawPassword) {
        return saveNew(Account.association(loginId, passwordEncoder.encode(rawPassword), regionId)).toPrincipal();
    }

    /** 아이디가 없거나 비밀번호가 틀리면 둘을 구분하지 않고 401 unauthorized. */
    @Transactional(readOnly = true)
    public AccountPrincipal authenticate(String loginId, String rawPassword) {
        Account account = accounts.findByLoginId(loginId).orElse(null);
        String hash = account == null ? dummyHash : account.getPasswordHash();
        if (!passwordEncoder.matches(rawPassword, hash) || account == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않습니다.");
        }
        return account.toPrincipal();
    }

    /** 세션의 loginId로 계정을 다시 읽는다. 세션 뒤에 계정이 없어졌으면 401 unauthorized. */
    @Transactional(readOnly = true)
    public AccountPrincipal getByLoginId(String loginId) {
        return accounts.findByLoginId(loginId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED))
                .toPrincipal();
    }

    private Account saveNew(Account account) {
        if (accounts.existsByLoginId(account.getLoginId())) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 쓰는 아이디입니다.");
        }
        try {
            return accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) { // 동시에 같은 아이디로 가입한 경우(uk_account_login_id)
            throw new ApiException(ErrorCode.CONFLICT, "이미 쓰는 아이디입니다.");
        }
    }
}
