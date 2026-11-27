package com.example.properity_project.service;

import com.example.properity_project.util.Errors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class FileStorageService {

    public record Stored(String storedName, String originalName, String contentType, long size) {}

    private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg",
            "png", "image/png", "pdf", "application/pdf");
    private static final long MAX_IMAGE = 5L * 1024 * 1024;
    private static final long MAX_PDF = 10L * 1024 * 1024;

    private final Path root;
    private final boolean inDatabase;
    private final JdbcTemplate jdbc;

    public FileStorageService(@Value("${app.storage.dir}") String dir,
                              @Value("${app.storage.mode:disk}") String mode,
                              JdbcTemplate jdbc) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.inDatabase = "db".equalsIgnoreCase(mode.trim());
        this.jdbc = jdbc;
        if (!inDatabase) Files.createDirectories(root);
    }

    public Stored store(MultipartFile file, boolean imageOnly) {
        if (file == null || file.isEmpty()) throw Errors.bad("Please choose a file to upload");
        String original = sanitise(file.getOriginalFilename());
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String type = TYPES.get(ext);
        if (type == null || (imageOnly && type.equals("application/pdf"))) {
            throw Errors.bad(imageOnly ? "Only JPG or PNG images are allowed" : "Only JPG, PNG or PDF files are allowed");
        }
        long limit = type.equals("application/pdf") ? MAX_PDF : MAX_IMAGE;
        if (file.getSize() > limit) throw Errors.bad("File is too large (limit " + (limit / 1024 / 1024) + " MB)");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw Errors.bad("Could not read the uploaded file");
        }
        if (!matchesMagic(type, data)) throw Errors.bad("File content does not match its type");
        String stored = UUID.randomUUID() + "." + ext;
        write(stored, data);
        return new Stored(stored, original, type, data.length);
    }

    public Resource load(String storedName) {
        if (inDatabase) {
            List<byte[]> rows = jdbc.query("SELECT content FROM stored_files WHERE name = ?", (rs, i) -> rs.getBytes(1), storedName);
            if (rows.isEmpty()) throw Errors.notFound("File");
            return new ByteArrayResource(rows.get(0));
        }
        Path p = root.resolve(storedName).normalize();
        if (!p.startsWith(root) || !Files.exists(p)) throw Errors.notFound("File");
        return new FileSystemResource(p);
    }

    public boolean exists(String storedName) {
        if (inDatabase) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM stored_files WHERE name = ?", Integer.class, storedName);
            return n != null && n > 0;
        }
        return Files.exists(root.resolve(storedName).normalize());
    }

    public void writeNamed(String storedName, byte[] data) {
        if (!inDatabase && !root.resolve(storedName).normalize().startsWith(root)) throw new IllegalArgumentException("bad name");
        write(storedName, data);
    }

    public Stored storeBytes(byte[] data, String ext, String type, String originalName) {
        String stored = UUID.randomUUID() + "." + ext;
        write(stored, data);
        return new Stored(stored, originalName, type, data.length);
    }

    private void write(String name, byte[] data) {
        if (inDatabase) {
            jdbc.update("DELETE FROM stored_files WHERE name = ?", name);
            jdbc.update("INSERT INTO stored_files (name, content) VALUES (?, ?)", name, data);
            return;
        }
        try {
            Files.write(root.resolve(name), data);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save file", e);
        }
    }

    private static boolean matchesMagic(String type, byte[] h) {
        if (h.length < 4) return false;
        return switch (type) {
            case "image/jpeg" -> (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8;
            case "image/png" -> (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
            case "application/pdf" -> h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F';
            default -> false;
        };
    }

    private static String sanitise(String name) {
        if (name == null) return "file";
        String n = name.replace("\\", "/");
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
        return n.isBlank() ? "file" : n;
    }
}
