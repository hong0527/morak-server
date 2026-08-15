package com.morak.report.service;

import com.morak.match.service.MatchService;
import com.morak.report.dto.request.SanctionCommand;
import com.morak.report.entity.Sanction;
import com.morak.report.repository.SanctionRepository;
import com.morak.report.type.SanctionType;
import com.morak.session.service.SessionExitService;
import com.morak.session.type.LeftReason;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제재 적용의 단일 경로 (API명세서 AD-3·AD-4).
 *
 * <p><b>AD-3(신고 처리에 딸린 제재)와 AD-4(단독 적용)가 같은 메서드를 부른다.</b> 절차가
 * 세 단계인데 경로가 둘이면 한쪽만 진행 세션을 정리하거나 한쪽만 매칭 요청을 놓아 주는
 * 상태가 반드시 생긴다 — 그러면 제재당한 회원이 남의 세션에 계속 앉아 있거나, 대기열에
 * 이름이 남아 {@code uk_mr_active} 때문에 아무 요청도 못 하는 유령이 된다.
 *
 * <p>세 단계는 한 트랜잭션이다. 제재 행만 남고 퇴장이 실패하는 중간 상태를 허용하지 않는다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SanctionService {

    private static final Logger log = LoggerFactory.getLogger(SanctionService.class);

    private final SanctionRepository sanctionRepository;
    private final SessionExitService sessionExitService;
    private final MatchService matchService;
    private final Clock clock;

    /**
     * 제재를 걸고 그 즉시 효력이 미치는 자리를 정리한다.
     *
     * <p>① {@code sanction} INSERT — 이후의 모든 요청은 전역 인터셉터 ④가 막는다.
     * ② 진행 중인 세션에서 강제 퇴장({@code left_reason=SANCTION}). 잔여 인원이 최소치
     * 미만이 되면 그 세션은 조기 종료된다.
     * ③ 대기 중인 매칭 요청 취소({@code active_member_id=NULL}).
     *
     * <p>②③을 직접 구현하지 않고 각 도메인의 기존 경로를 부른다. 퇴장은 조기 종료 검사와
     * 한 몸이고({@link SessionExitService}), 매칭 요청 해제는 조건 행 잠금·조건부 UPDATE·
     * 그림자 컬럼 비우기 3종 세트라({@link MatchService#cancelActiveRequest}) 여기서 다시
     * 쓰면 어느 한 조각을 빠뜨린다.
     *
     * @param caseId 근거 신고 케이스. 단독 제재면 null
     */
    public Sanction apply(Long memberId, Long caseId, SanctionCommand command, Long adminId) {
        command.validate();
        LocalDateTime now = LocalDateTime.now(clock);

        Sanction sanction = sanctionRepository.save(command.type() == SanctionType.TEMP
                ? Sanction.temporary(memberId, caseId, now, command.endsAt(now), adminId, now)
                : Sanction.permanent(memberId, caseId, now, adminId, now));

        sessionExitService.leaveAll(memberId, LeftReason.SANCTION);
        matchService.cancelActiveRequest(memberId);

        log.info("제재 적용: member={}, type={}, endsAt={}, case={}, admin={}",
                memberId, sanction.getType(), sanction.getEndsAt(), caseId, adminId);
        return sanction;
    }
}
