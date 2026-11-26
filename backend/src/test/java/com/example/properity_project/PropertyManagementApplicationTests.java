package com.example.properity_project;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"app.sms.twilio.account-sid=", "app.sms.twilio.verify-service-sid="})
class PropertyManagementApplicationTests {

    @Test
    void contextLoads() {
    }
}