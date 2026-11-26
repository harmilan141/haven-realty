package com.example.properity_project;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"spring.datasource.username=\tsa  ", "app.agent.email=  boss@example.com\t",
        "app.agent.password=Trim-Me-1 ", "app.storage.dir=target/test-storage-trim"})
class TrimSettingsTests {

    @Value("${spring.datasource.username}") String user;
    @Value("${app.agent.email}") String agentEmail;

    @Test
    void stripsStrayWhitespace() {
        assertEquals("sa", user);
        assertEquals("boss@example.com", agentEmail);
    }
}
