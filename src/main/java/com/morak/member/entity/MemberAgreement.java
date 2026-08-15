package com.morak.member.entity;

import com.morak.member.type.AgreementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 약관 동의 이력. member에 boolean 세 개로 두지 않는 이유는 "언제 동의했는지"가 사라지기 때문이다.
 * 개인정보 분쟁에서 필요한 것은 현재 상태가 아니라 시점이다.
 *
 * <p>필수 2종(TOS·PRIVACY)이 없는 회원은 서비스 API를 쓸 수 없다. MARKETING은 행의 유무가
 * 곧 수신 동의 여부라 별도 플래그를 두지 않고, 철회는 행 삭제로 표현한다.
 */
@Entity
@Table(
        name = "member_agreement",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ma",
                columnNames = {"member_id", "type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgreementType type;

    @Column(name = "agreed_at", nullable = false)
    private LocalDateTime agreedAt;

    private MemberAgreement(Long memberId, AgreementType type, LocalDateTime agreedAt) {
        this.memberId = memberId;
        this.type = type;
        this.agreedAt = agreedAt;
    }

    public static MemberAgreement agree(Long memberId, AgreementType type, LocalDateTime agreedAt) {
        return new MemberAgreement(memberId, type, agreedAt);
    }
}
