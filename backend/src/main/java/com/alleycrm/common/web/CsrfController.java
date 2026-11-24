package com.alleycrm.common.web;

import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프론트가 앱을 처음 열 때 부른다. 응답과 함께 XSRF-TOKEN 쿠키가 내려가고,
 * 이후 상태 변경 요청(POST · PUT · PATCH · DELETE)에 X-XSRF-TOKEN 헤더로 보낸다.
 */
@RestController
public class CsrfController {

    @GetMapping("/api/v1/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }
}
