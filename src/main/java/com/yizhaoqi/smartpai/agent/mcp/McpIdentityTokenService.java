package com.yizhaoqi.smartpai.agent.mcp;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

public class McpIdentityTokenService {
    private final Clock clock;

    public McpIdentityTokenService() { this(Clock.systemUTC()); }

    McpIdentityTokenService(Clock clock) { this.clock = clock; }

    public String issue(String serverId, String remoteTool, String userId, String signingKeyBase64, Duration ttl) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        byte[] keyBytes = Base64.getDecoder().decode(signingKeyBase64);
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        return Jwts.builder()
                .setIssuer("yerag")
                .setSubject(userId)
                .setAudience(serverId)
                .claim("tool", remoteTool)
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .setId(UUID.randomUUID().toString())
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
