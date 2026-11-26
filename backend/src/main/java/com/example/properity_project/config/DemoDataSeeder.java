package com.example.properity_project.config;

import com.example.properity_project.entity.*;
import com.example.properity_project.repository.*;
import com.example.properity_project.service.FileStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(2)
@ConditionalOnProperty(name = "app.seed-demo", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String PASSWORD = "Demo@12345";

    private final UserRepository users;
    private final PropertyRepository properties;
    private final PropertyImageRepository images;
    private final PropertyDocumentRepository docs;
    private final PropertyApprovalRepository approvals;
    private final EnquiryRepository enquiries;
    private final BuyerAgentMessageRepository messages;
    private final SellerAgentMessageRepository sellerMessages;
    private final SellerVerificationRepository verifications;
    private final VerificationDocumentRepository verificationDocs;
    private final VerificationHistoryRepository verificationHistory;
    private final SiteVisitRepository visits;
    private final PropertyOfferRepository offers;
    private final WishlistItemRepository wishlist;
    private final RecentlyViewedRepository recent;
    private final SaleTransactionRepository sales;
    private final RentalAgreementRepository rentals;
    private final NotificationRepository notifications;
    private final AuditLogRepository audit;
    private final PasswordEncoder encoder;
    private final FileStorageService storage;
    private final ObjectMapper mapper;

    private final LocalDateTime now = LocalDateTime.now();

    @Override
    public void run(String... args) throws Exception {
        if (users.countByRole("CUSTOMER") > 0) {
            repairMissingFiles();
            return;
        }
        JsonNode data;
        try (InputStream in = new ClassPathResource("seed/sample-data.json").getInputStream()) {
            data = mapper.readTree(in);
        }

        User agent = users.findFirstByRole("AGENT").orElseGet(() -> users.save(User.builder().fullName("Aarav Kapoor")
                .email("agent@haven.test").password(encoder.encode("Agent@12345")).phone("9000000000").city("Chandigarh")
                .state("Punjab").role("AGENT").emailVerified(true).phoneVerified(true).buyerEnabled(false).build()));

        String hash = encoder.encode(PASSWORD);
        Map<String, Long> userIds = new HashMap<>();
        userIds.put("agent", agent.getId());
        for (JsonNode u : data.get("users")) {
            User saved = users.save(User.builder().fullName(text(u, "fullName")).email(text(u, "email")).password(hash)
                    .phone(text(u, "phone")).address(text(u, "address")).city(text(u, "city")).state(text(u, "state"))
                    .postalCode(text(u, "postalCode")).dateOfBirth(LocalDate.parse(text(u, "dob"))).role("CUSTOMER")
                    .emailVerified(u.get("emailVerified").asBoolean()).phoneVerified(u.get("phoneVerified").asBoolean())
                    .buyerEnabled(true).sellerEnabled(u.get("sellerEnabled").asBoolean())
                    .accountEnabled(u.get("accountEnabled").asBoolean()).preferences(text(u, "preferences"))
                    .createdAt(ago(u, "createdDaysAgo")).build());
            userIds.put(saved.getEmail(), saved.getId());
        }

        List<Long> verificationIds = new ArrayList<>();
        for (JsonNode v : data.get("verifications")) {
            SellerVerification sv = verifications.save(SellerVerification.builder().userId(userIds.get(text(v, "user")))
                    .status(text(v, "status")).rejectionReason(text(v, "rejectionReason")).reviewNotes(text(v, "reviewNotes"))
                    .consentGiven(true).submittedAt(ago(v, "submittedDaysAgo"))
                    .reviewedAt(v.hasNonNull("reviewedDaysAgo") ? ago(v, "reviewedDaysAgo") : null)
                    .updatedAt(v.hasNonNull("reviewedDaysAgo") ? ago(v, "reviewedDaysAgo") : ago(v, "submittedDaysAgo")).build());
            verificationIds.add(sv.getId());
            for (JsonNode type : v.get("docs")) {
                var s = storage.storeBytes(("%PDF-1.4 sample " + type.asText().toLowerCase()).getBytes(), "pdf", "application/pdf",
                        type.asText().toLowerCase() + ".pdf");
                verificationDocs.save(VerificationDocument.builder().verificationId(sv.getId()).docType(type.asText())
                        .storedName(s.storedName()).originalName(s.originalName()).contentType(s.contentType())
                        .sizeBytes(s.size()).uploadedAt(sv.getSubmittedAt()).build());
            }
            for (JsonNode h : v.get("history")) {
                verificationHistory.save(VerificationHistory.builder().verificationId(sv.getId()).fromStatus(text(h, "from"))
                        .toStatus(text(h, "to")).note(text(h, "note"))
                        .actorId("PENDING".equals(text(h, "to")) ? sv.getUserId() : agent.getId())
                        .createdAt(ago(h, "daysAgo")).build());
            }
        }

        List<Property> props = new ArrayList<>();
        for (JsonNode p : data.get("properties")) {
            LocalDateTime created = ago(p, "createdDaysAgo");
            Property saved = properties.save(Property.builder().sellerId(userIds.get(text(p, "seller"))).title(text(p, "title"))
                    .propertyType(text(p, "type")).category(text(p, "category")).description(text(p, "description"))
                    .city(text(p, "city")).locality(text(p, "locality")).address(text(p, "address")).postalCode(text(p, "postalCode"))
                    .area(p.get("area").decimalValue()).measurementUnit(text(p, "unit")).price(p.get("price").decimalValue())
                    .bedrooms(p.get("bedrooms").asInt()).bathrooms(p.get("bathrooms").asInt()).furnishingStatus(text(p, "furnishing"))
                    .parkingAvailable(p.get("parking").asBoolean()).amenities(text(p, "amenities")).facing(text(p, "facing"))
                    .approvedBy(text(p, "approvedBy")).constructionType(text(p, "construction")).status(text(p, "status"))
                    .rejectionReason(text(p, "rejectionReason")).viewCount(p.get("viewCount").asInt())
                    .createdAt(created).updatedAt(created).build());
            props.add(saved);
            Color colour = Color.decode(text(p, "colour"));
            for (int i = 0; i < 2; i++) {
                var s = storage.storeBytes(render(saved.getTitle(), saved.getLocality() + ", " + saved.getCity(), colour, i),
                        "png", "image/png", "photo" + (i + 1) + ".png");
                images.save(PropertyImage.builder().propertyId(saved.getId()).storedName(s.storedName()).originalName(s.originalName())
                        .contentType("image/png").sortOrder(i).build());
            }
            var d = storage.storeBytes("%PDF-1.4 sample ownership".getBytes(), "pdf", "application/pdf", "ownership.pdf");
            docs.save(PropertyDocument.builder().propertyId(saved.getId()).docType("OWNERSHIP").storedName(d.storedName())
                    .originalName(d.originalName()).contentType(d.contentType()).uploadedAt(created).build());
            for (JsonNode a : p.get("approvals")) {
                approvals.save(PropertyApproval.builder().propertyId(saved.getId()).decision(text(a, "decision"))
                        .reason(text(a, "reason")).actorId(agent.getId()).createdAt(ago(a, "daysAgo")).build());
            }
        }

        List<Enquiry> enqs = new ArrayList<>();
        for (JsonNode e : data.get("enquiries")) {
            Long customer = userIds.get(text(e, "customer"));
            LocalDateTime created = ago(e, "createdDaysAgo");
            LocalDateTime updated = created;
            for (JsonNode m : e.get("messages")) if (ago(m, "daysAgo").isAfter(updated)) updated = ago(m, "daysAgo");
            Enquiry saved = enquiries.save(Enquiry.builder().propertyId(props.get(e.get("property").asInt()).getId())
                    .customerId(customer).enquiryType(text(e, "type")).message(text(e, "message")).status(text(e, "status"))
                    .priority(text(e, "priority")).closureReason(text(e, "closureReason"))
                    .followUpDate(e.hasNonNull("followUpInDays") ? LocalDate.now().plusDays(e.get("followUpInDays").asLong()) : null)
                    .createdAt(created).updatedAt(updated).build());
            enqs.add(saved);
            for (JsonNode m : e.get("messages")) {
                String role = text(m, "role");
                Long sender = switch (role) { case "AGENT" -> agent.getId(); case "CUSTOMER" -> customer; default -> 0L; };
                messages.save(BuyerAgentMessage.builder().enquiryId(saved.getId()).senderRole(role).senderId(sender)
                        .body(text(m, "body")).internalNote(m.path("internal").asBoolean(false)).createdAt(ago(m, "daysAgo")).build());
            }
            for (JsonNode v : e.get("visits")) {
                LocalDateTime at = LocalDate.now().plusDays(v.get("inDays").asLong()).atTime(v.get("hour").asInt(), 0);
                visits.save(SiteVisit.builder().enquiryId(saved.getId()).scheduledAt(at).status(text(v, "status"))
                        .notes(text(v, "notes")).createdAt(created).build());
            }
            for (JsonNode o : e.get("offers")) {
                offers.save(PropertyOffer.builder().enquiryId(saved.getId()).amount(o.get("amount").decimalValue())
                        .note(text(o, "note")).status(text(o, "status")).createdAt(ago(o, "daysAgo")).build());
            }
        }

        for (JsonNode s : data.get("sales")) {
            Enquiry e = enqs.get(s.get("enquiry").asInt());
            Property p = props.stream().filter(x -> x.getId().equals(e.getPropertyId())).findFirst().orElseThrow();
            LocalDate registry = s.hasNonNull("registryDaysAgo") ? LocalDate.now().minusDays(s.get("registryDaysAgo").asLong())
                    : s.hasNonNull("registryInDays") ? LocalDate.now().plusDays(s.get("registryInDays").asLong()) : null;
            sales.save(SaleTransaction.builder().enquiryId(e.getId()).propertyId(p.getId()).buyerId(e.getCustomerId())
                    .sellerId(p.getSellerId()).finalAmount(s.get("finalAmount").decimalValue())
                    .downPayment(s.get("downPayment").decimalValue()).dealDate(LocalDate.now().minusDays(s.get("dealDaysAgo").asLong()))
                    .registryDate(registry).totalCommission(s.get("commission").decimalValue())
                    .advanceReceived(s.get("advance").asBoolean()).balanceReceived(s.get("balance").asBoolean())
                    .status(text(s, "status")).notes(text(s, "notes")).createdAt(ago(s, "dealDaysAgo")).build());
        }

        for (JsonNode r : data.get("rentals")) {
            Enquiry e = enqs.get(r.get("enquiry").asInt());
            LocalDate start = LocalDate.now().minusDays(r.get("startDaysAgo").asLong());
            LocalDate end = start.plusDays(r.get("lengthDays").asLong());
            rentals.save(RentalAgreement.builder().enquiryId(e.getId()).propertyId(e.getPropertyId()).tenantId(e.getCustomerId())
                    .rentAmount(r.get("rent").decimalValue()).securityDeposit(r.get("deposit").decimalValue())
                    .startDate(start).endDate(end).renewalDate(end.minusDays(r.get("renewalDaysBeforeEnd").asLong()))
                    .status(text(r, "status")).notes(text(r, "notes")).createdAt(start.atTime(11, 0)).build());
        }

        for (JsonNode w : data.get("wishlist")) {
            wishlist.save(WishlistItem.builder().userId(userIds.get(w.get(0).asText())).propertyId(props.get(w.get(1).asInt()).getId())
                    .createdAt(daysAgo(w.get(2).asDouble())).build());
        }
        for (JsonNode w : data.get("recent")) {
            recent.save(RecentlyViewed.builder().userId(userIds.get(w.get(0).asText())).propertyId(props.get(w.get(1).asInt()).getId())
                    .viewedAt(daysAgo(w.get(2).asDouble())).build());
        }
        for (JsonNode m : data.get("sellerMessages")) {
            Long seller = userIds.get(text(m, "seller"));
            boolean fromSeller = "CUSTOMER".equals(text(m, "role"));
            sellerMessages.save(SellerAgentMessage.builder().sellerId(seller)
                    .propertyId(m.hasNonNull("property") ? props.get(m.get("property").asInt()).getId() : null)
                    .senderRole(fromSeller ? "SELLER" : "AGENT").senderId(fromSeller ? seller : agent.getId())
                    .body(text(m, "body")).createdAt(ago(m, "daysAgo")).build());
        }
        for (JsonNode n : data.get("notifications")) {
            notifications.save(Notification.builder().userId(userIds.get(text(n, "user"))).title(text(n, "title"))
                    .body(text(n, "body")).link(text(n, "link")).readFlag(n.get("read").asBoolean()).createdAt(ago(n, "daysAgo")).build());
        }
        for (JsonNode a : data.get("audit")) {
            JsonNode ref = a.get("ref");
            String kind = ref.get(0).asText();
            Long entityId = switch (kind) {
                case "user" -> userIds.get(ref.get(1).asText());
                case "verification" -> verificationIds.get(ref.get(1).asInt());
                case "property" -> props.get(ref.get(1).asInt()).getId();
                case "enquiry" -> enqs.get(ref.get(1).asInt()).getId();
                default -> null;
            };
            audit.save(AuditLog.builder().actorId(userIds.get(text(a, "actor"))).action(text(a, "action")).entityType(text(a, "entity"))
                    .entityId(entityId).details(text(a, "details")).createdAt(ago(a, "daysAgo")).build());
        }
        log.info("Sample data created: {} customers, {} properties, {} enquiries. Customer password {}, agent agent@haven.test / Agent@12345",
                userIds.size() - 1, props.size(), enqs.size(), PASSWORD);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private LocalDateTime ago(JsonNode n, String field) {
        return daysAgo(n.get(field).asDouble());
    }

    private LocalDateTime daysAgo(double days) {
        return now.minusSeconds(Math.round(days * 86400));
    }

    private void repairMissingFiles() throws Exception {
        int fixed = 0;
        Color[] palette = {new Color(0x1f3a5f), new Color(0x2d5a7b), new Color(0x3b6e5b), new Color(0x6b5b3b), new Color(0x4a3f6b), new Color(0x7b3f3f)};
        for (PropertyImage im : images.findAll()) {
            if (storage.exists(im.getStoredName())) continue;
            Property p = properties.findById(im.getPropertyId()).orElse(null);
            if (p == null) continue;
            Color c = palette[(int) (p.getId() % palette.length)];
            storage.writeNamed(im.getStoredName(), render(p.getTitle(), p.getLocality() + ", " + p.getCity(), c, im.getSortOrder()));
            fixed++;
        }
        for (PropertyDocument d : docs.findAll()) {
            if (!storage.exists(d.getStoredName())) { storage.writeNamed(d.getStoredName(), "%PDF-1.4 sample ownership".getBytes()); fixed++; }
        }
        for (VerificationDocument d : verificationDocs.findAll()) {
            if (!storage.exists(d.getStoredName())) { storage.writeNamed(d.getStoredName(), "%PDF-1.4 sample document".getBytes()); fixed++; }
        }
        if (fixed > 0) log.info("Recreated {} missing sample files", fixed);
    }

    private byte[] render(String title, String place, Color base, int variant) throws Exception {
        int w = 960, h = 640;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color top = variant == 1 ? base.brighter() : base;
        g.setPaint(new GradientPaint(0, 0, top.brighter().brighter(), 0, h, base.darker()));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 40));
        g.fillOval(w - 260, 50, 140, 140);
        g.setColor(new Color(0, 0, 0, 70));
        int x = 100 + variant * 40;
        g.fillRect(x, 300, 500, 260);
        g.fillPolygon(new int[]{x - 25, x + 250, x + 525}, new int[]{300, 195, 300}, 3);
        g.setColor(new Color(255, 226, 150, 190));
        for (int i = 0; i < 3; i++) g.fillRect(x + 55 + i * 155, 360, 75, 90);
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect(0, 560, w, 80);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 32));
        g.drawString(title.length() > 44 ? title.substring(0, 44) : title, 40, 606);
        g.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g.drawString(place + "  -  sample image", 40, 630);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
