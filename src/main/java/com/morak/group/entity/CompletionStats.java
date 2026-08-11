package com.morak.group.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 그룹별 완주 집계. 그룹당 한 행이므로 별도 식별자 없이 group_id가 PK다.
 *
 * <p>{@code completedCount}(분자)는 {@code endedMemberCount}(분모)의 부분집합이다.
 * 분자가 분모보다 큰 행이 조용히 저장되면 그룹 완주율이 1을 넘고, 그 값이 지표 대시보드와
 * 그룹 평균 계산에 그대로 흘러간다. 그래서 저장 전에 막는다.
 */
@Entity
@Table(name = "completion_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompletionStats {

    @Id
    @Column(name = "group_id")
    private Long groupId;

    /** 리포트 생성 대상 수(COMPLETED + REPORT_EXIT). LEFT는 세지 않는다. */
    @Column(name = "ended_member_count", nullable = false)
    private int endedMemberCount;

    @Column(name = "completed_count", nullable = false)
    private int completedCount;

    @Column(name = "aggregated_at", nullable = false)
    private LocalDateTime aggregatedAt;

    private CompletionStats(Long groupId, int endedMemberCount, int completedCount,
                            LocalDateTime aggregatedAt) {
        this.groupId = groupId;
        this.endedMemberCount = endedMemberCount;
        this.completedCount = completedCount;
        this.aggregatedAt = aggregatedAt;
    }

    public static CompletionStats aggregate(Long groupId, int endedMemberCount, int completedCount,
                                            LocalDateTime aggregatedAt) {
        validate(endedMemberCount, completedCount);
        return new CompletionStats(groupId, endedMemberCount, completedCount, aggregatedAt);
    }

    /** 리포트 정정(B6) 후 재집계. PK가 group_id라 행을 새로 만들 수 없다. */
    public void reaggregate(int endedMemberCount, int completedCount, LocalDateTime aggregatedAt) {
        validate(endedMemberCount, completedCount);
        this.endedMemberCount = endedMemberCount;
        this.completedCount = completedCount;
        this.aggregatedAt = aggregatedAt;
    }

    private static void validate(int endedMemberCount, int completedCount) {
        if (endedMemberCount < 0 || completedCount < 0) {
            throw new IllegalArgumentException(
                    "집계 수는 음수일 수 없습니다: ended=" + endedMemberCount
                            + ", completed=" + completedCount);
        }
        if (completedCount > endedMemberCount) {
            throw new IllegalArgumentException(
                    "완주자 수가 리포트 생성 대상 수를 넘을 수 없습니다: ended=" + endedMemberCount
                            + ", completed=" + completedCount);
        }
    }
}
