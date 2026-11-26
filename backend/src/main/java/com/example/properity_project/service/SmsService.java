package com.example.properity_project.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String accountSid;
    private final String authToken;
    private final String from;
    private final String countryCode;

    public SmsService(@Value("${app.sms.twilio.account-sid:}") String accountSid,
                      @Value("${app.sms.twilio.auth-token:}") String authToken,
                      @Value("${app.sms.twilio.from:}") String from,
                      @Value("${app.sms.country-code:+91}") String countryCode) {
        this.accountSid = accountSid.trim();
        this.authToken = authToken.trim();
        this.from = from.trim();
        this.countryCode = countryCode.trim();
        if (!isConfigured()) {
            log.warn("Twilio is not configured (TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_FROM) - SMS will not be sent");
        }
    }

    public boolean isConfigured() {
        return !accountSid.isEmpty() && !authToken.isEmpty() && !from.isEmpty();
    }

    @Async
    public void send(String phone, String text) {
        if (phone == null || phone.isBlank()) return;
        String to = phone.startsWith("+") ? phone : countryCode + phone;
        if (!isConfigured()) {
            log.info("[SMS not sent - Twilio not configured] to={}", to);
            return;
        }
        String sender = from.startsWith("MG") ? "MessagingServiceSid" : "From";
        String form = "To=" + enc(to) + "&" + sender + "=" + enc(from) + "&Body=" + enc(text);
        String auth = Base64.getEncoder().encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("Twilio could not send SMS to {}: HTTP {} {}", to, res.statusCode(), res.body());
            }
        } catch (Exception e) {
            log.warn("Could not send SMS to {}: {}", to, e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
