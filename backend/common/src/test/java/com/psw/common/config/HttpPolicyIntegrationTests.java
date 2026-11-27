package com.psw.common.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import jakarta.servlet.Filter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpPolicyIntegrationTests {
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    @BeforeAll
    static void createApplicationWithRealSecurityAndCors() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void closeContext() {
        if (context != null) context.close();
    }

    @Test
    void developmentPolicyAllowsAnonymousReadsAndWritesWithoutCsrfToken() throws Exception {
        mvc.perform(get("/probe")).andExpect(status().isOk()).andExpect(content().string("accepted"));
        mvc.perform(post("/probe")).andExpect(status().isOk()).andExpect(content().string("accepted"));
    }

    @Test
    void localFrontendPreflightAllowsCustomHeadersWithoutCredentials() throws Exception {
        mvc.perform(options("/probe").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type, X-Request-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers", "Content-Type, X-Request-Id"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void untrustedOriginsAndUnsupportedPreflightMethodsAreRejected() throws Exception {
        mvc.perform(options("/probe").header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/probe").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "TRACE"))
                .andExpect(status().isForbidden());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({GlobalSecurityConfig.class, CorsConfig.class, ProbeController.class})
    static class WebConfig {}

    @RestController
    static class ProbeController {
        @RequestMapping(path = "/probe", method = {RequestMethod.GET, RequestMethod.POST})
        String probe() { return "accepted"; }
    }
}
