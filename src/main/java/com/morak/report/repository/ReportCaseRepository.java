package com.morak.report.repository;

import com.morak.report.entity.ReportCase;
import com.morak.report.type.ReportTargetType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
