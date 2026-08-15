package com.morak.report.repository;

import com.morak.report.entity.ReportCase;
import com.morak.report.type.ReportStatus;
import com.morak.report.type.ReportTargetType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * AD-1의 필터가 status·severity·overdue·q 네 개이고 각각 생략 가능하다. 조건 조합이
 * 16가지라 메서드 이름 쿼리로는 감당이 안 되고, {@code (:status IS NULL OR ...)} 방식은
 * null enum 비교가 드라이버마다 다르게 풀려 조건이 조용히 빠진다
 * (SessionParticipantRepository의 SS-9 주석과 같은 이유). 그래서 이 하나만
 * {@link JpaSpecificationExecutor}로 조립한다 — 빠진 조건은 Java에서 걸러지므로 SQL에
 * 나가지 않는다.
 */
public interface ReportCaseRepository
        extends JpaRepository<ReportCase, Long>, JpaSpecificationExecutor<ReportCase> {

    /**
     * RP-1의 케이스 병합 판정. uk_rc_open이 (target_type, open_target_id)를 막으므로
     * 이 조회는 최대 1건이다 — 종결된 케이스는 open_target_id가 NULL이라 걸리지 않는다.
     */
    Optional<ReportCase> findByTargetTypeAndOpenTargetId(ReportTargetType targetType,
                                                         Long openTargetId);

    /**
     * AD-3 종결. <b>조건부 UPDATE가 "한 케이스는 한 번만 처리된다"의 실제 방어선이다.</b>
     * 서비스에서 status를 읽고 if로 막으면 동시에 들어온 두 처리가 둘 다 통과해 같은 회원에게
     * 제재가 두 번 걸린다 — 영향 행 수가 0이면 상대가 먼저 확정한 것이므로
     * {@code ALREADY_PROCESSED}로 끊는다.
     *
     * <p>불변식: status 변경과 {@code openTargetId}를 NULL로 비우는 일은 반드시 함께 일어난다.
     * 하나라도 빠지면 그 대상은 {@code uk_rc_open}에 영원히 걸려 다시 신고할 수 없다. 두 컬럼을
     * 한 문장에 묶어 두는 것이 그 보장이라 엔티티 메서드로 떼어내지 않는다.
     *
     * <p>종결된 케이스는 재오픈하지 않는다({@code WHERE status = :pending}). 재검토는 새 케이스다.
     *
     * <p>벌크 연산이라 flush·clear를 함께 건다. 남겨 두면 같은 트랜잭션에서 다시 읽은 케이스가
     * UPDATE 이전 상태로 보인다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ReportCase rc
               SET rc.status = :closed,
                   rc.openTargetId = null,
                   rc.restrictionReview = :restrictionReview
             WHERE rc.id = :caseId
               AND rc.status = :pending
            """)
    int close(@Param("caseId") Long caseId,
              @Param("closed") ReportStatus closed,
              @Param("restrictionReview") boolean restrictionReview,
              @Param("pending") ReportStatus pending);
}
