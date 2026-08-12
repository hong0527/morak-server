package com.morak.ai.entity;

import com.morak.ai.type.AiJudgmentType;
import com.morak.ai.type.AiTargetType;
import com.morak.ai.type.AiVerdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 판정 1건의 원본 기록.
 *
 * <p>target 규약: 판정 시점에 대상 행이 아직 존재하지 않으면 {@code target_type=MEMBER},
 * {@code target_id=member_id}로 기록한다. 얼굴 선검사(proof 저장 이전), 신고 상세 검열,
 * 게시글 소감 검열이 여기에 해당한다. 팩토리를 대상별로 나눈 것은 호출부가 target_type과
 * target_id를 짝이 맞지 않게 넣는 실수를 막기 위해서다.
 *
 * <p>{@code confidence}·{@code riskTypes}·{@code reason}은 관리자 콘솔(AD-2)에만 노출한다.
 */
@Entity
@Table(
        name = "ai_judgment",
        indexes = @Index(
                name = "idx_aj_target",
                columnList = "target_type, target_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiJudgment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiJudgmentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private AiTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiVerdict verdict;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    /** 탐지된 위험 유형 CSV(선정·폭력·개인정보·폭언·협박·스팸). */
    @Column(name = "risk_types", length = 100)
    private String riskTypes;

    @Column(length = 200)
    private String reason;

    @Column(name = "judged_at", nullable = false)
    private LocalDateTime judgedAt;

    private AiJudgment(AiJudgmentType type, AiTargetType targetType, Long targetId, AiVerdict verdict,
                       BigDecimal confidence, String riskTypes, String reason, LocalDateTime judgedAt) {
        this.type = type;
        this.targetType = targetType;
        this.targetId = targetId;
        this.verdict = verdict;
        this.confidence = confidence;
        this.riskTypes = riskTypes;
        this.reason = reason;
        this.judgedAt = judgedAt;
    }

    /** 저장된 인증에 대한 판정(검열·진위). */
    public static AiJudgment forProof(AiJudgmentType type, Long proofId, AiVerdict verdict,
                                      BigDecimal confidence, String riskTypes, String reason,
                                      LocalDateTime judgedAt) {
        return new AiJudgment(type, AiTargetType.PROOF, proofId, verdict, confidence, riskTypes, reason, judgedAt);
    }

    /** 대상 행이 아직 없는 판정(얼굴 선검사·신고 상세 검열·게시글 소감 검열). */
    public static AiJudgment forMember(AiJudgmentType type, Long memberId, AiVerdict verdict,
                                       BigDecimal confidence, String riskTypes, String reason,
                                       LocalDateTime judgedAt) {
        return new AiJudgment(type, AiTargetType.MEMBER, memberId, verdict, confidence, riskTypes, reason, judgedAt);
    }

    /** B1의 그룹 단위 완주 판정. */
    public static AiJudgment forGroup(AiJudgmentType type, Long groupId, AiVerdict verdict,
                                      BigDecimal confidence, String riskTypes, String reason,
                                      LocalDateTime judgedAt) {
        return new AiJudgment(type, AiTargetType.GROUP, groupId, verdict, confidence, riskTypes, reason, judgedAt);
    }
}
