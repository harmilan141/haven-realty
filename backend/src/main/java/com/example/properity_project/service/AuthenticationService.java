package com.example.properity_project.service;

import com.example.properity_project.dto.AuthDtos.*;
import com.example.properity_project.entity.User;
import com.example.properity_project.repository.UserRepository;
import com.example.properity_project.util.Errors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository users;
    private final UserService userService;
    private final JwtService jwt;
    private final AuthenticationManager authManager;
    private final PasswordEncoder encoder;
    private final OtpService otp;
    private final AuditService audit;
    private final NotificationService notifications;

    @Transactional
    public AuthResponse registerCustomer(RegisterRequest r) {
        String email = r.email().trim().toLowerCase();
        UserService.checkPasswordStrength(r.password());
        if (users.existsByEmail(email)) throw Errors.conflict("This email is already registered");
        if (users.existsByPhone(r.phone())) throw Errors.conflict("This mobile number is already registered");

        String purpose = r.purpose() == null ? "BUY" : r.purpose().toUpperCase();
        if (!purpose.matches("BUY|SELL|BOTH")) throw Errors.bad("Choose Buy, Sell or Both");

        User user = users.save(User.builder().fullName(r.fullName().trim()).email(email)
                .password(encoder.encode(r.password())).phone(r.phone()).dateOfBirth(r.dateOfBirth())
                .address(r.address()).city(r.city()).state(r.state()).postalCode(r.postalCode())
                .role("CUSTOMER").buyerEnabled(true).sellerEnabled(false)
                .emailVerified(true).phoneVerified(true).build());

        audit.log(user.getId(), "CUSTOMER_REGISTERED", "User", user.getId(), "purpose=" + purpose);
        notifications.notifyAgent("New customer registered", user.getFullName() + " joined as " + purpose.toLowerCase(), "/agent/customers");
        notifications.notify(user.getId(), "Welcome to Haven Realty",
                "Your account is ready. Start browsing properties.", "/properties", false);

        String next = purpose.equals("BUY") ? "DONE" : "SELLER_APPLICATION";
        return new AuthResponse(jwt.generateToken(user), userService.toView(user), next, null, null);
    }

    public AuthResponse login(LoginRequest r) {
        String email = r.email().trim().toLowerCase();
        authManager.authenticate(new UsernamePasswordAuthenticationToken(email, r.password()));
        User user = users.findByEmail(email).orElseThrow(() -> new BadCredentialsException("bad"));
        String portal = r.portal() == null ? "CUSTOMER" : r.portal().toUpperCase();
        if (!user.getRole().equals(portal)) throw new BadCredentialsException("wrong portal");
        audit.log(user.getId(), "LOGIN", "User", user.getId(), portal);
        return new AuthResponse(jwt.generateToken(user), userService.toView(user), null, null, null);
    }

    @Transactional
    public void verify(User current, VerifyRequest r) {
        User u = users.findById(current.getId()).orElseThrow(() -> Errors.notFound("User"));
        String channel = r.channel().toUpperCase();
        if (!channel.matches("EMAIL|PHONE")) throw Errors.bad("Unknown channel");
        otp.consume(u, channel, "VERIFY", r.code());
        if (channel.equals("EMAIL")) u.setEmailVerified(true); else u.setPhoneVerified(true);
        audit.log(u.getId(), channel + "_VERIFIED", "User", u.getId(), null);
    }

    @Transactional
    public java.util.Map<String, Object> resend(User current, String channel) {
        User u = users.findById(current.getId()).orElseThrow(() -> Errors.notFound("User"));
        String ch = channel.toUpperCase();
        if (!ch.matches("EMAIL|PHONE")) throw Errors.bad("Unknown channel");
        String code = otp.issue(u, ch, "VERIFY");
        var out = new java.util.HashMap<String, Object>();
        out.put("sent", true);
        if (otp.isExposed()) out.put("devCode", code);
        return out;
    }

    @Transactional
    public java.util.Map<String, Object> forgotPassword(ForgotRequest r) {
        var out = new java.util.HashMap<String, Object>();
        out.put("message", "If that account exists, a reset code has been sent to its mobile number and email.");
        findByLogin(r.login()).ifPresent(u -> {
            String code = otp.issue(u, "BOTH", "RESET");
            if (otp.isExposed()) out.put("devCode", code);
        });
        return out;
    }

    @Transactional
    public void resetPassword(ResetRequest r) {
        User u = findByLogin(r.login()).orElseThrow(() -> Errors.bad("Invalid or expired code"));
        otp.consume(u, "BOTH", "RESET", r.code());
        UserService.checkPasswordStrength(r.newPassword());
        u.setPassword(encoder.encode(r.newPassword()));
        audit.log(u.getId(), "PASSWORD_RESET", "User", u.getId(), null);
    }

    private java.util.Optional<User> findByLogin(String login) {
        String v = login.trim().replaceAll("[\\s-]", "");
        if (v.startsWith("+91")) v = v.substring(3);
        return v.matches("\\d{10}") ? users.findFirstByPhone(v) : users.findByEmail(v.toLowerCase());
    }
}
