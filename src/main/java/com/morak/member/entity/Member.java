package com.morak.member.entity;

import com.morak.member.type.AgeVerification;
import com.morak.member.type.MemberRole;
import com.morak.member.type.MemberStatus;
import com.morak.member.type.SocialProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원. 소셜 로그인으로만 가입한다.
 *
 * <p>SNS에서 받은 닉네임과 프로필 이미지는 본인 확인용으로 저장하되, 타인에게 보이는 화면에는
 * 서버가 만든 익명 닉네임({@code nickname})만 내보낸다. 카카오 닉네임이 실명인 경우가 많아
 * 그대로 노출하면 익명 서비스라는 전제가 깨진다.
 */
@Entity
@Table(
        name = "member",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_provider",
                columnNames = {"provider", "provider_user_id"}),
        indexes = @Index(
                name = "idx_member_withdraw",
                columnList = "status, delete_scheduled_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SocialProvider provider;

    /** 191자 제한은 utf8mb4 인덱스 키 길이 상한 때문이다. */
    @Column(name = "provider_user_id", nullable = false, length = 191)
    private String providerUserId;

    /** 타인에게 보이는 유일한 이름. 서버가 생성한다. */
    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(name = "sns_nickname", length = 50)
    private String snsNickname;

    @Column(name = "sns_profile_image_url", length = 500)
    private String snsProfileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_verification", nullable = false, length = 20)
    private AgeVerification ageVerification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    /** 조회용 캐시. 진실은 point_ledger의 delta 합이고, 어긋나면 원장이 옳다. */
    @Column(name = "point_balance", nullable = false)
    private int pointBalance;

    /** 조회용 캐시. 진실은 streak_day이고 언제든 재계산할 수 있다. 판정 근거로 쓰지 않는다. */
    @Column(name = "current_streak", nullable = false)
    private int currentStreak;

    /** 마지막 완주일. 연속 판정의 기준점이라 시각이 아니라 날짜다. */
    @Column(name = "last_completed_on")
    private LocalDate lastCompletedOn;

    @Column(name = "withdraw_requested_at")
    private LocalDateTime withdrawRequestedAt;

    @Column(name = "delete_scheduled_at")
    private LocalDateTime deleteScheduledAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private Member(SocialProvider provider, String providerUserId, String nickname,
                   String snsNickname, String snsProfileImageUrl, LocalDateTime now) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.nickname = nickname;
        this.snsNickname = snsNickname;
        this.snsProfileImageUrl = snsProfileImageUrl;
        this.role = MemberRole.PARTICIPANT;
        this.ageVerification = AgeVerification.REQUIRED;
        this.status = MemberStatus.ACTIVE;
        this.pointBalance = 0;
        this.currentStreak = 0;
        this.createdAt = now;
    }

    public static Member join(SocialProvider provider, String providerUserId, String nickname,
                              String snsNickname, String snsProfileImageUrl, LocalDateTime now) {
        return new Member(provider, providerUserId, nickname, snsNickname, snsProfileImageUrl, now);
    }

    /**
     * 생년월일을 확인해 연령 확인 상태를 확정한다. 만 14세 미만은 여기로 오지 않는다 —
     * 계정 자체를 만들지 않으므로 그 상태를 표현할 enum 값도 없다(★D7).
     */
    public void verifyAge(LocalDate birthDate, AgeVerification result) {
        this.birthDate = birthDate;
        this.ageVerification = result;
    }

    public void requestWithdrawal(LocalDateTime now, LocalDateTime deleteScheduledAt) {
        this.status = MemberStatus.WITHDRAW_PENDING;
        this.withdrawRequestedAt = now;
        this.deleteScheduledAt = deleteScheduledAt;
    }

    public void cancelWithdrawal() {
        this.status = MemberStatus.ACTIVE;
        this.withdrawRequestedAt = null;
        this.deleteScheduledAt = null;
    }

    /**
     * 탈퇴 확정 처리(B4). 컬럼을 NULL로 비우지 않고 값을 덮어쓴다.
     *
     * <p>NOT NULL 제약이 걸린 컬럼이 있고, provider_user_id를 비우면 uk_member_provider가
     * 여러 탈퇴 회원 사이에서 충돌해 재가입까지 막힌다.
     */
    public void anonymize(LocalDateTime now) {
        this.providerUserId = "deleted:" + this.id;
        this.nickname = "탈퇴회원";
        this.snsNickname = null;
        this.snsProfileImageUrl = null;
        this.birthDate = null;
        this.status = MemberStatus.DELETED;
        this.deletedAt = now;
    }

    public boolean isActive() {
        return this.status == MemberStatus.ACTIVE;
    }
}
