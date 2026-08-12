package com.morak.group.entity;

import com.morak.group.type.GroupMemberStatus;
import com.morak.group.type.LeftReason;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 그룹 멤버십. uk_gm이 (group_id, member_id)를 막고 있어 한 번 나간 그룹에는 다시 들어올 수 없다.
 *
 * <p>"활성 멤버십"은 status = ACTIVE 단일값으로만 판정하며, 회원은 활성 멤버십을 1건만 가진다.
 * 종료 배치(B1)가 잔류자를 LEFT가 아닌 COMPLETED로 옮기는 이유가 여기에 있다. 완주자를
 * ACTIVE로 남겨두면 활성 멤버십 검사에 계속 걸려 다음 챌린지 참여가 영구히 막히고,
 * 그렇다고 LEFT로 두면 중도 이탈자와 구분되지 않아 리포트 대상에서 빠진다.
 */
@Entity
@Table(
        name = "group_member",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_gm",
                columnNames = {"group_id", "member_id"}),
        indexes = @Index(
                name = "idx_gm_member",
                columnList = "member_id, status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupMemberStatus status;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    /** WITHDRAWAL·SANCTION은 서버만 지정한다. 사용자가 고를 수 있는 값은 PERSONAL~ETC뿐이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "left_reason", length = 30)
    private LeftReason leftReason;

    /** REPORT_EXIT의 근거 신고 케이스. 사후에 신고의 정당성을 판단할 때 되짚는 연결이다. */
    @Column(name = "exit_case_id")
    private Long exitCaseId;

    private GroupMember(Long groupId, Long memberId, LocalDateTime joinedAt) {
        this.groupId = groupId;
        this.memberId = memberId;
        this.status = GroupMemberStatus.ACTIVE;
        this.joinedAt = joinedAt;
    }

    public static GroupMember join(Long groupId, Long memberId, LocalDateTime joinedAt) {
        return new GroupMember(groupId, memberId, joinedAt);
    }

    /** 중도 이탈(GR-3·AU-4 탈퇴·AD-4 제재). */
    public void leave(LeftReason reason, LocalDateTime leftAt) {
        this.status = GroupMemberStatus.LEFT;
        this.leftReason = reason;
        this.leftAt = leftAt;
    }

    /**
     * 신고 접수에 따른 즉시 퇴장(RP-1). 신고자 본인이 그룹 접근을 잃는다.
     *
     * <p>LEFT와 분리하는 이유는 정당한 신고자가 완주 판정과 개인 리포트에서 배제되지
     * 않도록 하기 위해서다. 사용자가 고른 사유가 아니므로 leftReason은 비워 둔다.
     */
    public void reportExit(Long exitCaseId, LocalDateTime leftAt) {
        this.status = GroupMemberStatus.REPORT_EXIT;
        this.exitCaseId = exitCaseId;
        this.leftAt = leftAt;
    }

    /** 종료 배치(B1)가 잔류 멤버를 완주 처리한다. 퇴장이 아니므로 leftAt은 기록하지 않는다. */
    public void complete() {
        this.status = GroupMemberStatus.COMPLETED;
    }

    public boolean isActive() {
        return this.status == GroupMemberStatus.ACTIVE;
    }
}
