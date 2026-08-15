package com.morak.session.service;

import com.morak.common.dto.PageParams;
import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.point.entity.PointLedger;
import com.morak.point.repository.PointLedgerRepository;
import com.morak.point.type.PointReason;
import com.morak.session.dto.request.AppealCreateRequest;
import com.morak.session.dto.response.AppealCreateResponse;
import com.morak.session.dto.response.MyAppealResponse;
import com.morak.session.entity.AppealCase;
import com.morak.session.entity.Eviction;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.AppealCaseRepository;
import com.morak.session.repository.EvictionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.type.AppealStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AP-1 퇴출 이의 신청 (NFR-402, 리스크 대응 — AI 오탐).
 *
 * <p><b>없는 퇴출과 남의 퇴출을 같은 403으로 끊는다.</b> 404를 주면 evictionId를 훑어
 * "그 번호의 퇴출이 실재한다"를 알아낼 수 있고, 누가 언제 쫓겨났는지는 그 자체로 민감하다.
 * 주문(SR-5)이 반대로 통일된 것과 원칙은 같다 — 응답이 갈리는 지점을 만들지 않되, 어느
 * 쪽으로 통일할지는 그 자원의 기본 응답을 따른다.
 *
 * <p>신청만으로는 아무것도 되돌아가지 않는다. 퇴출도 포인트도 그대로이고, 원복은 관리자가
 * AD-6에서 인용할 때만 일어난다.
 */
@Service
@Transactional
public class AppealService {

    private static final Logger log = LoggerFactory.getLogger(AppealService.class);

    /** AP-2 정렬 — 최근 접수가 위. 동률은 번호 큰 쪽이 앞이라 페이지 경계가 흔들리지 않는다. */
    private static final Sort MY_APPEALS_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AppealCaseRepository appealCaseRepository;
    private final EvictionRepository evictionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final Clock clock;
    private final int slaHours;
    private final int fileDeadlineDays;

    public AppealService(AppealCaseRepository appealCaseRepository,
                         EvictionRepository evictionRepository,
                         SessionParticipantRepository sessionParticipantRepository,
                         PointLedgerRepository pointLedgerRepository,
                         Clock clock,
                         // 이의에는 신고 같은 등급 구분이 없다. 24시간은 피해자가 계속
                         // 노출되는 고위험 신고의 기한이고, 이의는 이미 벌어진 퇴출을
                         // 검토하는 일이라 일반 기한을 쓴다(명세 AP-1 부수효과).
                         @Value("${morak.report.sla-hours.normal}") int slaHours,
                         @Value("${morak.appeal.file-deadline-days}") int fileDeadlineDays) {
        this.appealCaseRepository = appealCaseRepository;
        this.evictionRepository = evictionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.pointLedgerRepository = pointLedgerRepository;
        this.clock = clock;
        this.slaHours = slaHours;
        this.fileDeadlineDays = fileDeadlineDays;
    }

    public AppealCreateResponse file(Long memberId, Long evictionId, AppealCreateRequest request) {
        Eviction eviction = evictionRepository.findById(evictionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (!eviction.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        // 재전송이 기한 초과보다 먼저다 — 접수에 성공한 클라이언트의 재시도가 기한이 지난 뒤
        // 도착해도 "이미 접수됨"을 보고 조용히 끝나야 한다(SS-4의 409 우선과 같은 논리).
        if (appealCaseRepository.existsByEvictionId(evictionId)) {
            throw new BusinessException(ErrorCode.APPEAL_ALREADY_FILED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 기한의 기산점은 세션 종료가 아니라 퇴출 시각이다. 당사자는 퇴출 응답(SS-4)으로 그
        // 순간 통지받고, 세션 종료 기준이면 세션이 길수록 기한이 늘어나는 우연이 생긴다.
        // 정각까지는 접수한다 — 경계 처리 방향은 제재 종료·충전 만료·탈퇴 파기와 같다.
        if (now.isAfter(eviction.getCreatedAt().plusDays(fileDeadlineDays))) {
            throw new BusinessException(ErrorCode.APPEAL_DEADLINE_PASSED);
        }
        AppealCase appeal = AppealCase.file(evictionId, memberId, request.reasonText(), now,
                now.plusHours(slaHours));
        try {
            appealCaseRepository.saveAndFlush(appeal);
        } catch (DataIntegrityViolationException e) {
            // 위의 존재 검사를 동시에 통과한 두 번째 신청. 실제 방어선은 uk_ap_eviction이고
            // 여기서는 그 제약 위반을 계약상의 409로 옮겨 준다.
            throw new BusinessException(ErrorCode.APPEAL_ALREADY_FILED);
        }
        log.info("퇴출 이의 접수: appeal={}, eviction={}, member={}, 기한={}",
                appeal.getId(), evictionId, memberId, appeal.getSlaDueAt());
        return AppealCreateResponse.from(appeal);
    }

    /**
     * AP-2 내 이의 목록. URL이 본인 스코프({@code /members/me})라 소유권 검사가 따로 없고,
     * 남의 evictionId를 훑는 경로 자체가 성립하지 않는다 — AP-1이 403으로 감추는 것을 여기서는
     * 조회 조건이 대신한다.
     *
     * <p>인용의 원복 두 값은 저장하지 않고 남긴 기록에서 되짚는다. 환급액은 원장
     * ({@code APPEAL_REFUND}) 줄이고, 원장에 없으면 0이다 — 되돌릴 차감이 없어 역분개도 없던
     * 경우(AD-6)까지 처리 응답과 같은 답을 내야 한다. 완주 소급은 참가 행의 {@code completed}다
     * — 퇴출자의 완주 표시는 인용 소급으로만 생긴다.
     */
    @Transactional(readOnly = true)
    public PageResponse<MyAppealResponse> getMyAppeals(Long memberId, Integer page, Integer size) {
        PageParams params = PageParams.of(page, size);
        Page<AppealCase> appeals = appealCaseRepository.findByMemberId(
                memberId, params.toPageable(MY_APPEALS_SORT));
        // 페이지 분량의 근거를 한 번에 읽는다. 행마다 퇴출·원장·참가를 조회하면
        // 20건짜리 페이지가 쿼리 61개가 된다.
        Map<Long, Eviction> evictions = evictionRepository
                .findAllById(appeals.getContent().stream().map(AppealCase::getEvictionId).toList())
                .stream()
                .collect(Collectors.toMap(Eviction::getId, Function.identity()));
        List<Long> acceptedEvictionIds = appeals.getContent().stream()
                .filter(appeal -> appeal.getStatus() == AppealStatus.ACCEPTED)
                .map(AppealCase::getEvictionId)
                .toList();
        Map<Long, Integer> refunds = pointLedgerRepository
                .findByMemberIdAndReasonAndRefTypeAndRefIdIn(memberId, PointReason.APPEAL_REFUND,
                        PointLedger.REF_EVICTION, acceptedEvictionIds)
                .stream()
                .collect(Collectors.toMap(PointLedger::getRefId, PointLedger::getDelta));
        Map<Long, Boolean> completedBySession = sessionParticipantRepository
                .findBySessionIdInOrderByIdAsc(acceptedEvictionIds.stream()
                        .map(id -> evictions.get(id).getSessionId())
                        .toList())
                .stream()
                .filter(participant -> participant.getMemberId().equals(memberId))
                .collect(Collectors.toMap(SessionParticipant::getSessionId,
                        SessionParticipant::isCompleted));
        return PageResponse.of(appeals, appeal -> {
            Eviction eviction = evictions.get(appeal.getEvictionId());
            boolean accepted = appeal.getStatus() == AppealStatus.ACCEPTED;
            return MyAppealResponse.of(appeal, eviction,
                    accepted ? refunds.getOrDefault(eviction.getId(), 0) : null,
                    accepted ? completedBySession.getOrDefault(eviction.getSessionId(), false)
                             : null);
        });
    }
}
