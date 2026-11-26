package com.example.properity_project.service;

import com.example.properity_project.entity.User;
import com.example.properity_project.entity.VerificationToken;
import com.example.properity_project.repository.UserRepository;
import com.example.properity_project.repository.VerificationTokenRepository;
import com.example.properity_project.util.Errors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final SmsService sms;

    @Value("${app.dev-expose-otp:false}")
    private boolean exposeOtp;

    public boolean isExposed() {
        return exposeOtp;
    }

    @Transactional
    public String issue(User user, String channel, String purpose) {
        tokens.invalidateOpen(user.getId(), channel, purpose, LocalDateTime.now());
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        tokens.save(VerificationToken.builder().userId(user.getId()).channel(channel).purpose(purpose)
                .codeHash(encoder.encode(code)).expiresAt(LocalDateTime.now().plusMinutes(15)).build());
        String what = "RESET".equals(purpose) ? "reset your password"
                : "EMAIL".equals(channel) ? "verify your email" : "verify your mobile number";
        String text = code + " is your Haven Realty code to " + what + ". It expires in 15 minutes. Do not share it.";
        if ("EMAIL".equals(channel) || "BOTH".equals(channel)) {
            mail.send(user.getEmail(), "Your Haven Realty code", text);
        }
        if ("PHONE".equals(channel) || "BOTH".equals(channel)) {
            if (sms.usesVerify()) sms.startVerification(user.getPhone());
            else sms.send(user.getPhone(), text);
        }
        return code;
    }

    @Transactional
    public void consume(User user, String channel, String purpose, String code) {
        VerificationToken t = tokens.findFirstByUserIdAndChannelAndPurposeAndUsedAtIsNullOrderByIdDesc(
                user.getId(), channel, purpose).orElseThrow(() -> Errors.bad("Invalid or expired code"));
        if (t.getExpiresAt().isBefore(LocalDateTime.now()) || t.getAttempts() >= 5) {
            throw Errors.bad("Invalid or expired code");
        }
        boolean ok = code != null && encoder.matches(code.trim(), t.getCodeHash());
        if (!ok && !"EMAIL".equals(channel) && sms.usesVerify()) {
            ok = sms.checkVerification(user.getPhone(), code);
        }
        if (!ok) {
            t.setAttempts(t.getAttempts() + 1);
            tokens.save(t);
            throw Errors.bad("Invalid or expired code");
        }
        t.setUsedAt(LocalDateTime.now());
        tokens.save(t);
    }
}
