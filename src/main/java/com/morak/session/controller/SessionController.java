package com.morak.session.controller;

import com.morak.common.security.LoginMember;
import com.morak.session.dto.request.AbsenceEventRequest;
import com.morak.session.dto.request.LeaveSessionRequest;
import com.morak.session.dto.request.SessionGoalRequest;
import com.morak.session.dto.response.AbsenceEventResponse;
import com.morak.session.dto.response.LivekitTokenResponse;
import com.morak.session.dto.response.PauseResumeResponse;
import com.morak.session.dto.response.PauseStartResponse;
import com.morak.session.dto.response.SessionDetailResponse;
import com.morak.session.dto.response.SessionGoalResponse;
import com.morak.session.dto.response.SessionResultResponse;
import com.morak.session.service.AbsenceJudgeService;
import com.morak.session.service.PauseService;
import com.morak.session.service.SessionExitService;
import com.morak.session.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final AbsenceJudgeService absenceJudgeService;
    private final PauseService pauseService;
    private final SessionExitService sessionExitService;

    @GetMapping("/{sessionId}")
    public SessionDetailResponse getSession(@LoginMember Long memberId,
                                            @PathVariable Long sessionId) {
        return sessionService.getSession(memberId, sessionId);
    }

    /** SS-8. 종료된 세션에서만 답한다 — 진행 중에 열면 409 SESSION_NOT_ENDED다. */
    @GetMapping("/{sessionId}/result")
    public SessionResultResponse getResult(@LoginMember Long memberId,
                                           @PathVariable Long sessionId) {
        return sessionService.getResult(memberId, sessionId);
    }

    /** SS-2. 요청 본문이 없다. 발급 대상은 언제나 토큰의 주인 본인이다. */
    @PostMapping("/{sessionId}/token")
    public LivekitTokenResponse issueToken(@LoginMember Long memberId,
                                           @PathVariable Long sessionId) {
        return sessionService.issueToken(memberId, sessionId);
    }

    @PutMapping("/{sessionId}/goal")
    public SessionGoalResponse updateGoal(@LoginMember Long memberId,
                                          @PathVariable Long sessionId,
                                          @Valid @RequestBody SessionGoalRequest request) {
        return sessionService.updateGoal(memberId, sessionId, request);
    }

    /** SS-4. 보고 대상은 요청 본문이 아니라 JWT가 정한다 — 남의 자리비움은 보고할 수 없다. */
    @PostMapping("/{sessionId}/absence-events")
    public AbsenceEventResponse reportAbsence(@LoginMember Long memberId,
                                              @PathVariable Long sessionId,
                                              @Valid @RequestBody AbsenceEventRequest request) {
        return absenceJudgeService.report(memberId, sessionId, request);
    }

    @PostMapping("/{sessionId}/pause")
    public PauseStartResponse startPause(@LoginMember Long memberId,
                                         @PathVariable Long sessionId) {
        return pauseService.start(memberId, sessionId);
    }

    @DeleteMapping("/{sessionId}/pause")
    public PauseResumeResponse resumeFromPause(@LoginMember Long memberId,
                                               @PathVariable Long sessionId) {
        return pauseService.resume(memberId, sessionId);
    }

    /**
     * SS-7 자율 퇴장. 본문을 필수로 걸지 않고 받는 이유는, DELETE에 본문을 빠뜨린 요청도
     * 사유 누락과 같은 400 {@code REASON_REQUIRED}로 답하기 위해서다.
     */
    @DeleteMapping("/{sessionId}/participation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveSession(@LoginMember Long memberId,
                             @PathVariable Long sessionId,
                             @RequestBody(required = false) LeaveSessionRequest request) {
        sessionExitService.leaveOnRequest(memberId, sessionId,
                LeaveSessionRequest.requireRequestableReason(request));
    }
}
