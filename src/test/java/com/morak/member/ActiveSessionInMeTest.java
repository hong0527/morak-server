package com.morak.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.member.dto.response.MemberMeResponse;
import com.morak.member.service.MemberService;
import com.morak.session.dto.response.ActiveSessionResponse;
import com.morak.session.service.PauseService;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.service.SessionExitService;
import com.morak.session.type.LeftReason;
import com.morak.session.type.ParticipantStatus;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AU-2의 {@code activeSession}. 앱을 재시작한 클라이언트가 재접속 유예(D13, 90초) 안에
 * 자기 세션으로 돌아갈 계약상 유일한 진입점이다 — 이 필드가 비거나 남의 세션이 실리면
 * 복귀 경로가 끊기거나 남의 방으로 안내한다.
 */
@DisplayName("내 정보의 진행 중 세션")
class ActiveSessionInMeTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;
    private static final int PARTICIPANTS = 6;

    @Autowired
    private MemberService memberService;

    @Autowired
    private PauseService pauseService;

    @Autowired
    private SessionExitService sessionExitService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Test
    @DisplayName("진행 중 세션에 참가 중이면 그 세션이 실린다")
    void 진행_중이면_세션이_실린다() {
        // 이 테스트가 죽으면: 앱 재시작 후 90초 유예 안에 세션 번호를 되찾을 경로가 없다.
        // MT-1은 409만 주고, 사용자는 자기가 어느 방에 있었는지 모른 채 유예가 끝난다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        clock.fixAt(BASE_TIME.plusMinutes(5));

        ActiveSessionResponse active = memberService.getMe(memberIds.getFirst()).activeSession();

        assertThat(active).isNotNull();
        assertThat(active.sessionId()).isEqualTo(sessionId);
        assertThat(active.participantStatus()).isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(active.targetMinutes()).isEqualTo(TARGET_MINUTES);
        assertThat(active.startedAt()).isEqualTo(BASE_TIME);
        assertThat(active.endsAt()).isEqualTo(BASE_TIME.plusMinutes(TARGET_MINUTES));
    }

    @Test
    @DisplayName("화장실 모드 중에도 세션이 실리고 상태는 PAUSED다")
    void 화장실_모드_중에도_실린다() {
        // 이 테스트가 죽으면: PAUSED 상태가 판정에서 빠진 것이다. 화장실 모드 중에 앱이
        // 죽으면 복귀 경로가 없다 — 유예 처리(D13)는 PAUSED도 지키는데 이 필드만 빈다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        clock.fixAt(BASE_TIME.plusMinutes(5));
        pauseService.start(memberIds.getFirst(), sessionId);

        ActiveSessionResponse active = memberService.getMe(memberIds.getFirst()).activeSession();

        assertThat(active).isNotNull();
        assertThat(active.sessionId()).isEqualTo(sessionId);
        assertThat(active.participantStatus()).isEqualTo(ParticipantStatus.PAUSED);
    }

    @Test
    @DisplayName("세션이 없으면 null이다")
    void 세션이_없으면_null이다() {
        Long memberId = fixtures.joinMember();

        assertThat(memberService.getMe(memberId).activeSession()).isNull();
    }

    @Test
    @DisplayName("나간 세션과 끝난 세션은 실리지 않는다")
    void 나갔거나_끝난_세션은_실리지_않는다() {
        // 이 테스트가 죽으면: LEFT 참가 행이나 끝난 세션의 ACTIVE 행이 복귀 대상으로 잡히는
        // 것이다. 재입장 불가(SS-7)·종료된 방으로 안내하는 헛된 SS-2 왕복이 생긴다.
        List<Long> memberIds = fixtures.joinMembers(PARTICIPANTS);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, memberIds);
        Long leaver = memberIds.getFirst();
        Long stayer = memberIds.get(1);
        clock.fixAt(BASE_TIME.plusMinutes(5));
        sessionExitService.leaveOnRequest(leaver, sessionId, LeftReason.PERSONAL);

        assertThat(memberService.getMe(leaver).activeSession()).isNull();
        assertThat(memberService.getMe(stayer).activeSession()).isNotNull();

        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();

        MemberMeResponse afterEnd = memberService.getMe(stayer);
        assertThat(afterEnd.activeSession()).isNull();
    }
}
