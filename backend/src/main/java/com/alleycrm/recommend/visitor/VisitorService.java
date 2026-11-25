package com.alleycrm.recommend.visitor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 익명 방문자(FR-CRS-07, CONTRACTS_API 3.3). 로그인 전 탐색 기록은 브라우저의 방문자 ID로 남기고,
 * 같은 브라우저에서 로그인하거나 예약을 하면 그 손님에 잇는다. 다른 기기는 잇지 않는다.
 *
 * <p>쿠키는 이 서비스가 현재 HTTP 요청 · 응답에서 직접 읽고 쓴다. 부르는 쪽(C2 로그인 · 로그아웃,
 * C4 신청 단계 20, 탐색 기록)은 손님 ID만 넘긴다.
 */
@Service
public class VisitorService {

    private static final String ISSUED_IN_THIS_REQUEST = VisitorService.class.getName() + ".issuedAnonId";

    private final VisitorRepository visitors;
    private final VisitorCookie cookie;
    private final Clock clock;

    public VisitorService(VisitorRepository visitors, VisitorCookie cookie, Clock clock) {
        this.visitors = visitors;
        this.cookie = cookie;
        this.clock = clock;
    }

    /**
     * 이 브라우저의 방문자 ID(표 visitor의 id). 쿠키가 없거나 서버에 없는 ID면 새 방문자를 만들고
     * 응답에 쿠키를 싣는다. 탐색 기록을 남길 때 부른다.
     */
    @Transactional
    public long currentVisitorId() {
        ServletRequestAttributes http = currentHttp();
        Optional<VisitorRepository.Visitor> known = currentAnonId(http).flatMap(visitors::findByAnonId);
        if (known.isPresent()) {
            return known.get().id();
        }
        return issue(http, null);
    }

    /**
     * 이 브라우저의 방문자를 손님에 잇는다(로그인 성공 · 예약 신청 뒤).
     * 쿠키가 없거나 모르는 ID면 이을 기록이 없으니 아무것도 하지 않는다.
     * 다른 손님에 이미 이어진 방문자면 기존 연결은 두고 새 방문자 ID를 발급해 이 손님에 잇는다.
     */
    @Transactional
    public void linkCurrentBrowser(long customerId) {
        ServletRequestAttributes http = currentHttp();
        Optional<UUID> anonId = currentAnonId(http);
        if (anonId.isEmpty()) {
            return;
        }
        Optional<VisitorRepository.Visitor> visitor = visitors.findByAnonId(anonId.get());
        if (visitor.isEmpty()) {
            return;
        }
        Long linkedTo = visitor.get().customerId();
        if (linkedTo == null) {
            if (visitors.linkIfUnlinked(visitor.get().id(), customerId, now()) == 1) {
                return;
            }
            // 동시에 다른 요청이 먼저 이었다. 누구에게 이어졌는지 다시 본다.
            linkedTo = visitors.findByAnonId(anonId.get()).map(VisitorRepository.Visitor::customerId).orElse(null);
        }
        if (linkedTo != null && linkedTo == customerId) {
            return;
        }
        issue(http, customerId);
    }

    /** 이 브라우저의 방문자 쿠키를 지운다(로그아웃). 다음 탐색은 새 익명 방문자로 기록된다. */
    public void forgetCurrentBrowser() {
        cookie.clear(currentHttp().getResponse());
    }

    /**
     * 새 방문자를 만들고 응답에 쿠키를 싣는다. 같은 요청 안에서 다시 부르면 이 방문자를 쓰도록 요청 속성에도 둔다
     * (브라우저는 다음 요청부터 새 쿠키를 보낸다).
     */
    private long issue(ServletRequestAttributes http, Long customerId) {
        UUID anonId = UUID.randomUUID();
        long id = visitors.insert(anonId, customerId, customerId == null ? null : now());
        cookie.write(http.getResponse(), anonId);
        http.getRequest().setAttribute(ISSUED_IN_THIS_REQUEST, anonId);
        return id;
    }

    /** 이 요청에서 발급한 방문자가 있으면 그것, 없으면 쿠키의 방문자. */
    private Optional<UUID> currentAnonId(ServletRequestAttributes http) {
        if (http.getRequest().getAttribute(ISSUED_IN_THIS_REQUEST) instanceof UUID issued) {
            return Optional.of(issued);
        }
        return cookie.read(http.getRequest());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static ServletRequestAttributes currentHttp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes http)
                || http.getResponse() == null) {
            throw new IllegalStateException("방문자 쿠키는 HTTP 요청 안에서만 다룰 수 있습니다.");
        }
        return http;
    }
}
