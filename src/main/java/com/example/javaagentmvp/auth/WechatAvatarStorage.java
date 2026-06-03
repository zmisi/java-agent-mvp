package com.example.javaagentmvp.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class WechatAvatarStorage {

    private final WechatAuthProperties properties;

    public WechatAvatarStorage(WechatAuthProperties properties) {
        this.properties = properties;
    }

    public StoredAvatar store(long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "avatar file is required");
        }
        String contentType = file.getContentType() != null ? file.getContentType() : "";
        String extension = extensionFor(contentType, file.getOriginalFilename());
        try {
            return storeBytes(userId, file.getBytes(), contentType, extension);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to read avatar", ex);
        }
    }

    public StoredAvatar storeBytes(long userId, byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "avatar data is required");
        }
        if (bytes.length > 2 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "avatar file too large");
        }
        String safeType = contentType != null ? contentType : "";
        return storeBytes(userId, bytes, safeType, extensionFor(safeType, null));
    }

    private StoredAvatar storeBytes(long userId, byte[] bytes, String contentType, String extension) {
        try {
            Path root = Path.of(properties.avatarUploadDir()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path target = root.resolve(userId + extension);
            Files.write(target, bytes);
            String publicPath = "/uploads/wechat-avatars/" + userId + extension;
            return new StoredAvatar(publicPath, target);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to store avatar", ex);
        }
    }

    private static String extensionFor(String contentType, String originalFilename) {
        if (contentType.contains("png")) {
            return ".png";
        }
        if (contentType.contains("webp")) {
            return ".webp";
        }
        if (contentType.contains("gif")) {
            return ".gif";
        }
        if (originalFilename != null) {
            String lower = originalFilename.toLowerCase();
            if (lower.endsWith(".png")) {
                return ".png";
            }
            if (lower.endsWith(".webp")) {
                return ".webp";
            }
            if (lower.endsWith(".gif")) {
                return ".gif";
            }
        }
        return ".jpg";
    }

    public record StoredAvatar(String publicPath, Path filePath) {
    }
}
