package com.alleycrm.common.time;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.common.TestCsrf;
import com.alleycrm.TestcontainersConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.demo-tools.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@WithMockUser
class DemoClockControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AppClock appClock;

    @Autowired
    Clock clock;

    @AfterEach
    void reset() {
        appClock.reset();
    }

    @Test
    void Clock_빈과_AppClock은_같은_시계() {
        org.assertj.core.api.Assertions.assertThat(clock).isSameAs(appClock);
    }

    @Test
    void 분_단위로_앞당긴다() throws Exception {
        mvc.perform(post("/api/v1/demo/clock").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"advanceMinutes\": 1440}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offsetSeconds").value(86400));
    }

    @Test
    void 특정_시각으로_맞춘다() throws Exception {
        mvc.perform(post("/api/v1/demo/clock").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"at\": \"2026-12-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.now").value(org.hamcrest.Matchers.startsWith("2026-12-01T00:00")));
    }

    @Test
    void 둘_다_보내거나_둘_다_없으면_invalid() throws Exception {
        mvc.perform(post("/api/v1/demo/clock").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 되돌리면_offset_0() throws Exception {
        appClock.advance(java.time.Duration.ofHours(3));

        mvc.perform(delete("/api/v1/demo/clock").with(TestCsrf.csrf(mvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offsetSeconds").value(0));
        mvc.perform(get("/api/v1/demo/clock")).andExpect(jsonPath("$.offsetSeconds").value(0));
    }
}
