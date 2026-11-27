package com.psw.gateway;

import com.psw.gateway.filter.ContextInitFilter;
import com.psw.gateway.controller.GwExceptionHandler;
import com.psw.gateway.model.RequestContext;
import com.psw.gateway.utility.ContextInitUtil;
import com.psw.gateway.utility.ExceptionParserUtil;
import com.psw.gateway.utility.GwLogger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UnknownRouteIntegrationTest {
    private ConfigurableApplicationContext gatewayContext;
    private int gatewayPort;

    @TestConfiguration
    @EnableAutoConfiguration(exclude = {
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
    })
    @Import({
            ContextInitFilter.class,
            ContextInitUtil.class,
            ExceptionParserUtil.class,
            GwExceptionHandler.class
    })
    static class GwTestApp {
        @Bean
        GwLogger gwLogger() {
            return mock(GwLogger.class);
        }
    }

    @BeforeAll
    void startGateway() {
        gatewayContext = SpringApplication.run(
                GwTestApp.class,
                "--server.port=0",
                "--spring.config.name=isolated-route-test"
        );
        gatewayPort = Objects.requireNonNull(
                ((WebServerApplicationContext) gatewayContext).getWebServer(),
                "Gateway web server failed to start"
        ).getPort();
    }

    @Test
    public void unKnownRouteTo404 () throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + gatewayPort + "/unknown-route");
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(404, response.statusCode());
        assertEquals("Not found", response.body());
        GwLogger logger = gatewayContext.getBean(GwLogger.class);

        ArgumentCaptor<RequestContext> captor =
                ArgumentCaptor.forClass(RequestContext.class);

        verify(logger, times(1)).logRequest(captor.capture());

        RequestContext context = captor.getValue();

        assertEquals(404, context.getStatus());
        assertTrue(context.getAlerts().isEmpty());
    }

    @AfterAll
    void stopGateway() {
        if (gatewayContext != null) {
            gatewayContext.close();
        }
    }
}
