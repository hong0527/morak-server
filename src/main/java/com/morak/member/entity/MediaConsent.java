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
 * 캠 영상 온디바이스 분석 동의. 회원당 최대 1건이라 member_id가 곧 PK다.
 * 영상 자체는 서버로 오지 않고 자리비움 판정만 단말에서 이뤄진다(D17).
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

    /**
     * 재동의. 회원당 1행이라 두 번째 호출은 INSERT가 아니라 시각 갱신이다 —
     * 마지막으로 동의한 시각이 남아야 문구가 바뀌었을 때 누가 어느 시점 문구에
     * 동의했는지 따질 수 있다.
     */
    public void renew(LocalDateTime agreedAt) {
        this.agreedAt = agreedAt;
    }
}
