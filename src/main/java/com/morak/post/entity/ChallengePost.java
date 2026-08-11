package com.morak.post.entity;

import com.morak.common.type.BadgeCode;
import com.morak.common.type.GoalCategory;
import com.morak.post.type.PostStatus;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 완주 게시글. 그룹당 1건이며 삭제해도 행이 남아 재작성되지 않는다.
 *
 * <p>{@code authorAlias}를 member.nickname과 분리하는 이유가 이 엔티티의 핵심이다.
 * 닉네임을 그대로 쓰면 (분야·기간·작성일)이 같은 글 최대 6건을 묶어 외부인이 6인 그룹의
 * 구성원 전체와 각자의 인증률을 재구성할 수 있고, 신고하고 나간 사람까지 그 묶음으로
 * 추적된다. 그래서 게시 시점에 이 글에서만 쓰는 별칭을 새로 만들어 붙인다.
 *
 * <p>성적 스냅샷 5개(category·periodDays·provedDays·proofRate·completed)는 사용자가 정할 수
 * 없다. 서버가 final_report와 그룹에서 복사해 넣어야 완주 기록을 위조할 수 없다.
 */
@Entity
@Table(
        name = "challenge_post",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_post_member_group",
                columnNames = {"member_id", "group_id"}),
        indexes = @Index(
                name = "idx_post_list",
                columnList = "status, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengePost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "final_report_id", nullable = false)
    private Long finalReportId;

    /** 게시 시점에 생성한 이 글 전용 별칭. member.nickname과 이어지지 않는다. */
    @Column(name = "author_alias", nullable = false, length = 30)
    private String authorAlias;

    /** 사용자가 쓰는 유일한 값. 정규식 선차단과 AI 검열을 통과한 것만 들어온다. */
    @Column(length = 200)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoalCategory category;

    @Column(name = "period_days", nullable = false)
    private int periodDays;

    @Column(name = "proved_days", nullable = false)
    private int provedDays;

    @Column(name = "proof_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal proofRate;

    @Column(nullable = false)
    private boolean completed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostStatus status;

    @Column(name = "hidden_by_case_id")
    private Long hiddenByCaseId;

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private ChallengePost(Long memberId, Long groupId, Long finalReportId, String authorAlias,
                          String comment, GoalCategory category, int periodDays, int provedDays,
                          BigDecimal proofRate, boolean completed, LocalDateTime createdAt) {
        this.memberId = memberId;
        this.groupId = groupId;
        this.finalReportId = finalReportId;
        this.authorAlias = authorAlias;
        this.comment = comment;
        this.category = category;
        this.periodDays = periodDays;
        this.provedDays = provedDays;
        this.proofRate = proofRate;
        this.completed = completed;
        this.status = PostStatus.VISIBLE;
        this.createdAt = createdAt;
    }

    /**
     * 게시글을 등록한다. 사용자가 입력하는 값은 {@code comment} 하나뿐이라 첫 자리에 둔다.
     *
     * <p>나머지는 전부 서버가 채운다. {@code authorAlias}는 게시 시점에 생성하고,
     * {@code category}는 그룹에서, {@code reportPeriodDays}부터 {@code reportCompleted}까지는
     * 해당 final_report에서 복사한다({@code reportPeriodDays}는 final_report.total_days).
     * 클라이언트가 보낸 성적 값을 그대로 받으면 완주 기록을 위조할 수 있다.
     */
    public static ChallengePost publish(String comment,
                                        Long memberId, Long groupId, Long finalReportId,
                                        String authorAlias, GoalCategory category,
                                        int reportPeriodDays, int reportProvedDays,
                                        BigDecimal reportProofRate, boolean reportCompleted,
                                        LocalDateTime createdAt) {
        return new ChallengePost(memberId, groupId, finalReportId, authorAlias, comment, category,
                reportPeriodDays, reportProvedDays, reportProofRate, reportCompleted, createdAt);
    }

    /** 신고 처리로 글을 가린다. 어떤 케이스로 가려졌는지 남겨야 이의 제기 때 근거를 찾는다. */
    public void hide(Long caseId, LocalDateTime now) {
        this.status = PostStatus.HIDDEN;
        this.hiddenByCaseId = caseId;
        this.hiddenAt = now;
    }

    /** 오탐 구제. DELETED는 복구 대상이 아니므로 HIDDEN에서만 되돌린다. */
    public void unhide() {
        if (this.status != PostStatus.HIDDEN) {
            throw new IllegalStateException("가려진 글만 되돌릴 수 있습니다: status=" + this.status);
        }
        this.status = PostStatus.VISIBLE;
        this.hiddenByCaseId = null;
        this.hiddenAt = null;
    }

    /**
     * 작성자 삭제. 행을 지우지 않는다 — uk_post_member_group이 그룹당 1건을 강제하므로
     * 행을 지우면 삭제 후 재작성이 열려 성적을 골라 다시 올릴 수 있다.
     */
    public void delete() {
        this.status = PostStatus.DELETED;
    }

    /** 리포트 정정(B6) 반영. 원본이 바뀌었는데 스냅샷이 남으면 위조 불가 원칙이 깨진다. */
    public void refreshSnapshot(int reportPeriodDays, int reportProvedDays,
                                BigDecimal reportProofRate, boolean reportCompleted) {
        this.periodDays = reportPeriodDays;
        this.provedDays = reportProvedDays;
        this.proofRate = reportProofRate;
        this.completed = reportCompleted;
    }

    /** 리포트와 같은 규칙으로 매번 계산한다. 저장해 두면 정정된 성적과 배지가 어긋난다. */
    public BadgeCode getBadgeCode() {
        return BadgeCode.of(completed, proofRate.doubleValue());
    }

    public boolean isVisible() {
        return this.status == PostStatus.VISIBLE;
    }
}
