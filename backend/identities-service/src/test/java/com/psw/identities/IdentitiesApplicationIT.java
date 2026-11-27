package com.psw.identities;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

@Testcontainers
@SpringBootTest(classes = IdentitiesApplication.class, properties = {
        "spring.config.name=isolated-test", "server.port=0", "routes.audit=http://127.0.0.1:9"
})
class IdentitiesApplicationIT {
    @Container
    private static final MariaDBContainer database = new MariaDBContainer("mariadb:11.4");

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
    }

    @Test
    void contextLoads() {
    }
}
