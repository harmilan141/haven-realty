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
import java.util.regex.Pattern;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final Pattern APPROVED = Pattern.compile("\"status\"\\s*:\\s*\"approved\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String accountSid;
    private final String authToken;
    private final String from;
    private final String countryCode;
    private final String verifyServiceSid;

    public SmsService(@Value("${app.sms.twilio.account-sid:}") String accountSid,
                      @Value("${app.sms.twilio.auth-token:}") String authToken,
                      @Value("${app.sms.twilio.from:}") String from,
                      @Value("${app.sms.country-code:+91}") String countryCode,
                      @Value("${app.sms.twilio.verify-service-sid:}") String verifyServiceSid) {
        this.accountSid = accountSid.trim();
        this.authToken = authToken.trim();
        this.from = from.trim();
        this.countryCode = countryCode.trim();
        this.verifyServiceSid = verifyServiceSid.trim();
        if (!isConfigured() && !usesVerify()) {
            log.warn("Twilio is not configured (TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN and TWILIO_VERIFY_SERVICE_SID or TWILIO_FROM) - SMS will not be sent");
        }
    }

    public boolean isConfigured() {
        return !accountSid.isEmpty() && !authToken.isEmpty() && !from.isEmpty();
    }

    public boolean usesVerify() {
        return !accountSid.isEmpty() && !authToken.isEmpty() && !verifyServiceSid.isEmpty();
    }

    @Async
    public void send(String phone, String text) {
        if (phone == null || phone.isBlank()) return;
        String to = e164(phone);
        if (!isConfigured()) {
            log.info("[SMS not sent - Twilio not configured] to={}", to);
            return;
        }
        String sender = from.startsWith("MG") ? "MessagingServiceSid" : "From";
        HttpResponse<String> res = post("https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json",
                "To=" + enc(to) + "&" + sender + "=" + enc(from) + "&Body=" + enc(text));
        if (res != null && res.statusCode() >= 300) {
            log.warn("Twilio could not send SMS to {}: HTTP {} {}", to, res.statusCode(), res.body());
        }
    }

    @Async
    public void startVerification(String phone) {
        if (phone == null || phone.isBlank()) return;
        String to = e164(phone);
        HttpResponse<String> res = post("https://verify.twilio.com/v2/Services/" + verifyServiceSid + "/Verifications",
                "To=" + enc(to) + "&Channel=sms");
        if (res != null && res.statusCode() >= 300) {
            log.warn("Twilio Verify could not send a code to {}: HTTP {} {}", to, res.statusCode(), res.body());
        }
    }

    public boolean checkVerification(String phone, String code) {
        if (phone == null || code == null || code.isBlank()) return false;
        HttpResponse<String> res = post("https://verify.twilio.com/v2/Services/" + verifyServiceSid + "/VerificationCheck",
                "To=" + enc(e164(phone)) + "&Code=" + enc(code.trim()));
        return res != null && res.statusCode() < 300 && APPROVED.matcher(res.body()).find();
    }

    private HttpResponse<String> post(String url, String form) {
        String auth = Base64.getEncoder().encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Twilio request failed: {}", e.getMessage());
            return null;
        }
    }

    private String e164(String phone) {
        return phone.startsWith("+") ? phone : countryCode + phone;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
