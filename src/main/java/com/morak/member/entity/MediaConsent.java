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
 * 미디어(인증 사진) 활용 동의. 회원당 최대 1건이라 member_id가 곧 PK다.
 *
 * <p>동의 철회는 행 삭제로 표현한다. B4 탈퇴 확정 시에도 이 행을 함께 삭제한다.
 */
@Entity
@Table(name = "media_consent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaConsent {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "agreed_at", nullable = false)
    private LocalDateTime agreedAt;

    private MediaConsent(Long memberId, LocalDateTime agreedAt) {
        this.memberId = memberId;
        this.agreedAt = agreedAt;
    }

    public static MediaConsent agree(Long memberId, LocalDateTime agreedAt) {
        return new MediaConsent(memberId, agreedAt);
    }
}
