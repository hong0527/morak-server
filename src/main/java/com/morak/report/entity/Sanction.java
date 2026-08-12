package com.morak.report.entity;

import com.morak.report.type.SanctionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 제재. 전역 인터셉터가 매 요청마다 유효 제재를 확인한다.
 *
 * <p>{@code endsAt}은 TEMP에만 있고 PERMANENT는 NULL이다. 그래서 만료 판정을 단순 비교로 쓸 수 없고
 * {@link #isEffectiveAt(LocalDateTime)}로 통일한다.
 */
@Entity
@Table(
        name = "sanction",
        indexes = @Index(
                name = "idx_sanction_member",
                columnList = "member_id, starts_at, ends_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sanction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 신고 케이스 없이 운영 판단으로 내리는 제재가 있어 NULL을 허용한다. */
    @Column(name = "case_id")
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SanctionType type;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private Sanction(Long memberId, Long caseId, SanctionType type, LocalDateTime startsAt,
                     LocalDateTime endsAt, Long adminId, LocalDateTime createdAt) {
        this.memberId = memberId;
        this.caseId = caseId;
        this.type = type;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.adminId = adminId;
        this.createdAt = createdAt;
    }

    public static Sanction temporary(Long memberId, Long caseId, LocalDateTime startsAt,
                                     LocalDateTime endsAt, Long adminId, LocalDateTime createdAt) {
        return new Sanction(memberId, caseId, SanctionType.TEMP, startsAt, endsAt, adminId,
                createdAt);
    }

    public static Sanction permanent(Long memberId, Long caseId, LocalDateTime startsAt,
                                     Long adminId, LocalDateTime createdAt) {
        return new Sanction(memberId, caseId, SanctionType.PERMANENT, startsAt, null, adminId,
                createdAt);
    }

    /** 만료 경계는 endsAt 시각에 제재가 풀리도록 열린 구간으로 본다. */
    public boolean isEffectiveAt(LocalDateTime now) {
        return !this.startsAt.isAfter(now) && (this.endsAt == null || this.endsAt.isAfter(now));
    }

    /**
     * 유효 제재 목록의 대표 종류. 영구가 하나라도 섞여 있으면 영구다.
     *
     * <p>인터셉터(403 응답)와 AU-2(내 정보)가 같은 값을 내보내야 해서 계산을 한 곳에 둔다.
     * 빈 목록은 "제재 없음"이지 "TEMP 제재"가 아니므로 호출부가 먼저 걸러야 한다.
     */
    public static SanctionType representativeType(List<Sanction> effectiveSanctions) {
        requireNotEmpty(effectiveSanctions);
        boolean hasPermanent = effectiveSanctions.stream()
                .anyMatch(sanction -> sanction.getType() == SanctionType.PERMANENT);
        return hasPermanent ? SanctionType.PERMANENT : SanctionType.TEMP;
    }

    /**
     * 유효 제재 목록의 대표 종료 시각. 영구 제재가 하나라도 섞여 있으면 끝나지 않으므로 null,
     * 기간형만 있으면 가장 늦게 끝나는 시각이다.
     *
     * <p>여기서 null은 "영구 제재라 끝이 없다"는 뜻 하나뿐이다. 빈 목록에도 null을 돌려주면
     * 제재가 없는 회원과 영구 제재 회원이 같은 값으로 보인다 — 그래서 빈 목록은 거부한다.
     */
    public static LocalDateTime latestEndsAt(List<Sanction> effectiveSanctions) {
        requireNotEmpty(effectiveSanctions);
        boolean hasPermanent = effectiveSanctions.stream()
                .anyMatch(sanction -> sanction.getEndsAt() == null);
        if (hasPermanent) {
            return null;
        }
        return effectiveSanctions.stream()
                .map(Sanction::getEndsAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private static void requireNotEmpty(List<Sanction> effectiveSanctions) {
        if (effectiveSanctions.isEmpty()) {
            throw new IllegalArgumentException("유효 제재가 없는 목록이다");
        }
    }
}
