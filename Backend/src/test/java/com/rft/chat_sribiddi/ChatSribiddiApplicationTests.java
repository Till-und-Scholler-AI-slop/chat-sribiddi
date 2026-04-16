package com.rft.chat_sribiddi;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires PostgreSQL and Redis infrastructure — not available in CI")
class ChatSribiddiApplicationTests {

	@Test
	void contextLoads() {
	}

}
