package com.alleycrm.common.time;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** 기본 설정(시연 도구 꺼짐)에서는 시각 조작 API가 없다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoClockDisabledTest {

    @Autowired
    MockMvc mvc;

    @Test
    @WithMockUser
    void 시연_도구가_꺼져_있으면_404() throws Exception {
        mvc.perform(get("/api/v1/demo/clock")).andExpect(status().isNotFound());
    }
}
