package com.morak.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 로그인 토큰을 만들고 검증한다.
 *
 * <p>토큰에는 회원 번호만 담는다. 회원 상태나 제재 여부는 넣지 않는다.
 * 토큰이 24시간 유효한데 그 안에 제재가 걸리면 24시간 동안 무시되기 때문이다.
 * 상태 검사는 요청마다 DB 현재값으로 한다.
 */
@Component
public class JwtProvider {

    private final SecretKey key;
    private final long expireMillis;
    private final Clock clock;

    public JwtProvider(@Value("${morak.jwt.secret}") String secret,
                       @Value("${morak.jwt.expire-hours}") long expireHours,
                       Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expireMillis = expireHours * 60 * 60 * 1000;
        this.clock = clock;
    }

    public String createToken(Long memberId) {
        long now = clock.millis();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMillis))
                .signWith(key)
                .compact();
    }

    /**
     * 토큰에서 회원 번호를 꺼낸다.
     *
     * @throws JwtException 서명이 맞지 않거나 만료됐을 때. 호출부가 401로 바꾼다.
     */
    public Long parseMemberId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> new Date(clock.millis()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.valueOf(claims.getSubject());
    }
}
