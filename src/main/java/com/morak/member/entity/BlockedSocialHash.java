package com.morak.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재가입 차단 목록. PERMANENT 제재 이력자가 탈퇴(B4)할 때만 등재하며 TEMP는 등재하지 않는다.
 *
 * <p>탈퇴 후에는 SNS 식별자 원문을 보유하지 않으므로 해시만으로 재가입을 판정한다.
 * 순수 SHA-256은 provider + providerUserId 조합이 좁아 역산이 가능해서, pepper를 환경변수로
 * 주입한 HMAC-SHA256 값을 저장한다.
 */
@Entity
@Table(name = "blocked_social_hash")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BlockedSocialHash {

    private static final String REASON_SANCTION_PERMANENT = "SANCTION_PERMANENT";

    @Id
    @Column(name = "social_hash", length = 64)
    private String socialHash;

    @Column(nullable = false, length = 30)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** NULL이면 무기한 차단. 보존 기한은 법무 확인 후 확정한다. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    private BlockedSocialHash(String socialHash, String reason,
                             LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.socialHash = socialHash;
        this.reason = reason;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static BlockedSocialHash sanctionPermanent(String socialHash,
                                                     LocalDateTime createdAt,
                                                     LocalDateTime expiresAt) {
        return new BlockedSocialHash(socialHash, REASON_SANCTION_PERMANENT, createdAt, expiresAt);
    }

    public boolean isEffective(LocalDateTime now) {
        return this.expiresAt == null || this.expiresAt.isAfter(now);
    }
}
