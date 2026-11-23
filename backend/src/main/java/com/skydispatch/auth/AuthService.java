package com.skydispatch.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skydispatch.config.SkyProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * 单账号登录与 HMAC 令牌签发/校验。
 *
 * <p>为什么不用 Spring Security + JWT 库：
 * 当前目标是把内部演示平台补到"有登录、有权限边界"的程度，单账号 + 无状态签名令牌
 * 已经能挡住"任何人打开链接就能下单/派单"的问题，同时不引入额外依赖与复杂配置。
 * 真要做企业级认证，应替换成 OIDC/LDAP + 标准 JWT，并保留本类的接口形状。
 *
 * <p>令牌格式：{@code base64url(payload).base64url(HmacSHA256(payload))}，
 * payload 是 {@code {"sub":"admin","iat":...,"exp":...}}。
 * 服务端只保存密钥，不保存会话，天然支持多实例部署。
 */
@Service
public class AuthService {

    public record LoginResult(String token, String username, long expiresAtEpochSeconds) {
    }

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SkyProperties properties;
    private final ObjectMapper objectMapper;

    public AuthService(SkyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验账号密码并签发令牌。
     *
     * @return 校验失败返回 {@code null}，由 Controller 转成 401
     */
    public LoginResult login(String username, String password) {
        SkyProperties.Auth cfg = properties.auth();
        if (username == null || password == null) {
            return null;
        }
        // 定长比较，避免按字符提前返回带来的时序侧信道
        if (!constantEquals(cfg.username(), username) || !constantEquals(cfg.password(), password)) {
            return null;
        }
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + Math.max(1, cfg.tokenTtlMinutes()) * 60;
        String payload = writePayload(cfg.username(), now, expiresAt);
        String token = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString(hmac(payload));
        return new LoginResult(token, cfg.username(), expiresAt);
    }

    /**
     * 校验令牌并返回用户名；无效/过期返回 {@code null}。
     */
    public String verifyAndGetUser(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return null;
        }
        String payloadJson;
        byte[] providedSignature;
        try {
            payloadJson = new String(DECODER.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            providedSignature = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        // 先验签再解析内容，避免把未签名的 JSON 当可信数据
        if (!MessageDigest.isEqual(hmac(payloadJson), providedSignature)) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            long expiresAt = payload.path("exp").asLong(0);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                return null;
            }
            String subject = payload.path("sub").asText(null);
            return subject == null || subject.isBlank() ? null : subject;
        } catch (Exception ex) {
            return null;
        }
    }

    private String writePayload(String subject, long issuedAt, long expiresAt) {
        try {
            return objectMapper.writeValueAsString(new TokenPayload(subject, issuedAt, expiresAt));
        } catch (Exception ex) {
            throw new IllegalStateException("令牌 payload 序列化失败", ex);
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.auth().secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC 初始化失败，请检查 sky.auth.secret", ex);
        }
    }

    private static boolean constantEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private record TokenPayload(String sub, long iat, long exp) {
    }
}
