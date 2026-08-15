package com.morak.match.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 매칭 배제 관계. 방향이 있는 쌍이라 대칭 차단은 2행으로 표현한다.
 *
 * <p>신고 1건(RP-1)은 항상 (신고자→대상)·(대상→신고자) 2행을 함께 만든다. 한쪽만 넣으면
 * 대기열을 어느 방향에서 훑느냐에 따라 둘이 다시 매칭될 수 있다.
 *
 * <p>차단은 영구다 — 해제 경로가 없다(★D6). 그리고 신고를 해도 진행 중인 세션에서는
 * 아무도 나가지 않는다. 이 테이블은 다음 매칭부터 효력이 생긴다.
 *
 * <p>MT-1의 6인 확정은 대기열에서 사람을 빼는 게 아니라 후보 집합 안의 모든 쌍을 확인해
 * 조합에서 배제한다. 신고자와 대상이 각각 다른 사람과는 매칭될 수 있어야 하기 때문이다.
 */
@Entity
@Table(
        name = "match_block",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mb",
                columnNames = {"member_id", "blocked_member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchBlock {

    /** 등재 근거. 현재는 신고 하나뿐이라 enum을 두지 않았다. */
    private static final String SOURCE_REPORT = "REPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이 회원의 대기열에서 */
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 이 회원을 배제한다 */
    @Column(name = "blocked_member_id", nullable = false)
    private Long blockedMemberId;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private MatchBlock(Long memberId, Long blockedMemberId, String source,
                       LocalDateTime createdAt) {
        this.memberId = memberId;
        this.blockedMemberId = blockedMemberId;
        this.source = source;
        this.createdAt = createdAt;
    }

    public static MatchBlock byReport(Long memberId, Long blockedMemberId,
                                      LocalDateTime createdAt) {
        return new MatchBlock(memberId, blockedMemberId, SOURCE_REPORT, createdAt);
    }
}
