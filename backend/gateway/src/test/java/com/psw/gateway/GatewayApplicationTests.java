package com.psw.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = GatewayApplication.class, properties = {
        "spring.config.name=isolated-test", "server.port=0",
        "routes.documents=http://127.0.0.1:9", "routes.incident=http://127.0.0.1:9"
})
class GatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
