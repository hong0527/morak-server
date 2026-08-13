package com.morak.session.service;

import com.morak.common.dto.PageParams;
import com.morak.common.dto.PageResponse;
import com.morak.member.repository.MemberNickname;
import com.morak.member.repository.MemberRepository;
import com.morak.session.dto.response.AdminSessionResponse;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.session.type.SessionStatus;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AD-7 진행 중 세션 모니터 (API명세서 AD-7, FR-701).
 *
 * <p><b>조회 전용이다.</b> 관리자가 세션에 개입하는 경로(강제 종료·강제 퇴장)는 v1 범위 밖이라
 * 이 클래스에는 상태를 바꾸는 메서드가 없다.
 *
 * <p>관리자 여부는 여기서 보지 않는다 — {@code /api/admin/**} 전체를 전역 인터셉터의 ③ 역할
 * 검사가 막는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SessionAdminService {

    /** 최근 시작한 세션이 위로 온다. 모니터가 보는 것은 지금 돌아가는 세션이다. */
    private static final Sort MONITOR_SORT =
            Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final MemberRepository memberRepository;

    /** 생략된 {@code status}는 조건 자체를 만들지 않는다(AD-1과 같은 규칙). */
    public PageResponse<AdminSessionResponse> getSessions(SessionStatus status, Integer page,
                                                          Integer size) {
        PageParams params = PageParams.of(page, size);
        Page<LiveSession> sessions = status == null
                ? liveSessionRepository.findAll(params.toPageable(MONITOR_SORT))
                : liveSessionRepository.findByStatus(status, params.toPageable(MONITOR_SORT));

        Map<Long, List<SessionParticipant>> participants = sessionParticipantRepository
                .findBySessionIdInOrderByIdAsc(
                        sessions.getContent().stream().map(LiveSession::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(SessionParticipant::getSessionId));
        Map<Long, String> nicknames = nicknamesOf(participants.values().stream()
                .flatMap(List::stream)
                .map(SessionParticipant::getMemberId)
                .distinct()
                .toList());

        return PageResponse.of(sessions, session -> AdminSessionResponse.of(session,
                participants.getOrDefault(session.getId(), List.of()), nicknames));
    }

    /**
     * 관리자에게도 서버가 만든 익명 닉네임만 내보낸다. SNS 닉네임은 실명인 경우가 많아,
     * 콘솔이라는 이유로 그쪽을 보여주면 익명 서비스라는 전제가 운영 화면에서만 깨진다.
     */
    private Map<Long, String> nicknamesOf(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return memberRepository.findByIdIn(memberIds).stream()
                .collect(Collectors.toMap(MemberNickname::getId, MemberNickname::getNickname));
    }
}
