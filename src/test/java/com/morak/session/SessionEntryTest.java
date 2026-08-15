package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.dto.request.MediaConsentRequest;
import com.morak.member.service.MemberService;
import com.morak.session.dto.request.SessionGoalRequest;
import com.morak.session.dto.response.LivekitTokenResponse;
import com.morak.session.service.SessionExitService;
import com.morak.session.service.SessionService;
import com.morak.session.type.LeftReason;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionStatus;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AU-6 → SS-2 → SS-3, 사용자가 세션에 실제로 들어가는 길.
 *
 * <p>세션 <b>안에서</b> 일어나는 일(경고·퇴출·Pause·종료 정산)은 촘촘히 덮여 있는데, 정작
 * <b>들어가는 문</b>은 아무도 열어 본 적이 없었다. SS-2가 막히면 매칭이 아무리 잘 돼도 화면에
 * 아무것도 뜨지 않는다 — 그 뒤의 모든 테스트가 통과하면서도 서비스는 멈춘 상태가 된다.
 *
 * <p>SS-2가 지는 검사는 넷이다: 세션 존재 → 참가 자격 → 세션 상태 → 캠 분석 동의.
 * <b>순서가 계약이다</b>(§0-3) — 상태를 먼저 보면 참가자가 아닌 사람이 세션 번호를 훑어
 * "그 세션이 존재하고 끝났다"를 알아낼 수 있다.
 */
@DisplayName("세션 진입")
class SessionEntryTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private SessionExitService sessionExitService;

    @Autowired
    private MockMvc mockMvc;

    // ── AU-6 캠 분석 동의 ──

    @Test
    @DisplayName("동의는 행의 존재로 기록되고 다시 눌러도 하나다")
    void 동의가_기록된다() {
        // 이 테스트가 죽으면: 동의 없이 세션에 들어가거나(SS-2가 못 막는다), 다시 누를 때마다
        // 행이 쌓인다. 미동의를 저장하지 않으므로 행의 유무가 곧 동의 여부다.
        Long memberId = fixtures.joinVerifiedMember("au6-agree");
        assertThat(fixtures.count("media_consent", "member_id = ?", memberId)).isZero();

        memberService.agreeMediaConsent(memberId, new MediaConsentRequest(true));
        memberService.agreeMediaConsent(memberId, new MediaConsentRequest(true));

        assertThat(fixtures.count("media_consent", "member_id = ?", memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("미동의는 저장하지 않고 400으로 끊는다")
    void 미동의는_저장되지_않는다() {
        // 이 테스트가 죽으면: false 행이 남아 "동의를 취소했다"로도 "아직 안 했다"로도 읽히는
        // 값이 된다. 철회가 v1 범위 밖이라 그 상태를 해석할 규칙 자체가 없다.
        Long memberId = fixtures.joinVerifiedMember("au6-decline");

        assertThatThrownBy(() ->
                memberService.agreeMediaConsent(memberId, new MediaConsentRequest(false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(fixtures.countAll("media_consent")).isZero();
    }

    // ── SS-2 접속 토큰 ──

    @Test
    @DisplayName("동의한 참가자는 자기 방의 접속 토큰을 받는다")
    void 토큰_발급_정상_경로() {
        // 이 테스트가 죽으면: 매칭이 끝나도 화면에 아무도 보이지 않는다. 방 이름이 세션과
        // 어긋나면 6명이 각자 다른 방에 들어가 혼자 앉아 있게 된다.
        List<Long> members = fixtures.joinMembers(2);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, members);
        Long memberId = members.getFirst();
        memberService.agreeMediaConsent(memberId, new MediaConsentRequest(true));

        LivekitTokenResponse token = sessionService.issueToken(memberId, sessionId);

        assertThat(token.token()).isNotBlank();
        assertThat(token.url()).isNotBlank();
        assertThat(token.roomName())
                .isEqualTo(fixtures.session(sessionId).getLivekitRoomName());
        assertThat(token.identity()).contains(String.valueOf(memberId));
        // 오디오 publish 권한은 토큰에서 뺀다(D23)
        assertThat(token.canPublishAudio()).isFalse();
        assertThat(token.expiresInSeconds()).isPositive();
    }

    @Test
    @DisplayName("여러 번 받아도 안전하고 같은 방을 가리킨다")
    void 토큰_재발급은_부수효과가_없다() {
        // 이 테스트가 죽으면: 끊겼다 재접속하는 경로(D13)가 막힌다. 프론트는 연결이 끊기면
        // SS-2를 다시 부르게 돼 있어서(§0-7) 이 호출에 부수효과가 있으면 안 된다.
        List<Long> members = fixtures.joinMembers(2);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, members);
        Long memberId = members.getFirst();
        memberService.agreeMediaConsent(memberId, new MediaConsentRequest(true));

        LivekitTokenResponse first = sessionService.issueToken(memberId, sessionId);
        LivekitTokenResponse second = sessionService.issueToken(memberId, sessionId);

        assertThat(second.roomName()).isEqualTo(first.roomName());
        assertThat(second.identity()).isEqualTo(first.identity());
        // 부수효과가 없다 = 참가자 상태도 세션 상태도 그대로다
        assertThat(fixtures.participant(sessionId, memberId).getStatus())
                .isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(fixtures.session(sessionId).getStatus()).isEqualTo(SessionStatus.LIVE);
    }

    @Test
    @DisplayName("동의하지 않으면 참가자여도 토큰을 받지 못한다")
    void 동의가_없으면_토큰이_없다() {
        // 이 테스트가 죽으면: 캠 분석 동의 없이 얼굴 판정이 도는 세션에 들어간다. 동의가
        // 법적 근거라 이 게이트는 SS-2에만 있다 — 여기가 뚫리면 다른 곳에 막을 자리가 없다.
        List<Long> members = fixtures.joinMembers(2);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, members);

        assertThatThrownBy(() -> sessionService.issueToken(members.getFirst(), sessionId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONSENT_REQUIRED);
    }

    @Test
    @DisplayName("참가자가 아니면 세션이 끝났는지조차 알 수 없다")
    void 검사_순서가_존재를_감춘다() {
        // 이 테스트가 죽으면: 세션 상태를 참가 자격보다 먼저 보게 된 것이다. 그 순간 남이
        // 세션 번호를 훑어 "그 세션이 존재하고 끝났다"를 알아낼 수 있다(§0-3).
        List<Long> members = fixtures.joinMembers(2);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, members);
        Long stranger = fixtures.joinVerifiedMember("ss2-stranger");
        memberService.agreeMediaConsent(stranger, new MediaConsentRequest(true));

        // 진행 중이든 끝난 뒤든 남에게는 같은 응답이어야 한다
        assertThatThrownBy(() -> sessionService.issueToken(stranger, sessionId))
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_SESSION_PARTICIPANT);
        fixtures.execute("UPDATE live_session SET status = 'ENDED' WHERE id = ?", sessionId);
        assertThatThrownBy(() -> sessionService.issueToken(stranger, sessionId))
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_SESSION_PARTICIPANT);

        // 없는 세션은 404다
        assertThatThrownBy(() -> sessionService.issueToken(stranger, 999_999L))
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("스스로 나간 참가자는 다시 들어오지 못한다")
    void 퇴장한_참가자는_재입장할_수_없다() {
        // 이 테스트가 죽으면: 자율 퇴장(SS-7)이 무의미해진다. 나갔다 들어오기를 반복하며
        // 자리비움 판정을 피할 수 있다.
        List<Long> members = fixtures.joinMembers(3);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, members);
        Long memberId = members.getFirst();
        memberService.agreeMediaConsent(memberId, new MediaConsentRequest(true));
        sessionExitService.leaveOnRequest(memberId, sessionId, LeftReason.PERSONAL);

        assertThatThrownBy(() -> sessionService.issueToken(memberId, sessionId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_SESSION_PARTICIPANT);
    }

    @Test
    @DisplayName("끝난 세션의 참가자에게는 종료를 알린다")
    void 끝난_세션에는_토큰이_없다() {
        // 이 테스트가 죽으면: 종료된 세션에 접속 토큰이 나가 이미 닫힌 방으로 붙으려 한다.
        // 참가자에게만 SESSION_ENDED가 보이는 것이 §0-3의 규칙이다.
        List<Long> members = fixtures.joinMembers(2);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, members);
        Long memberId = members.getFirst();
        memberService.agreeMediaConsent(memberId, new MediaConsentRequest(true));
        fixtures.execute("UPDATE live_session SET status = 'ENDED' WHERE id = ?", sessionId);

        assertThatThrownBy(() -> sessionService.issueToken(memberId, sessionId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_ENDED);
    }

    // ── SS-3 오늘 할 일 ──

    @Test
    @DisplayName("오늘 할 일은 세션 중 몇 번이든 고칠 수 있다")
    void 세션_목표_등록과_수정() {
        // 이 테스트가 죽으면: 참가자가 무엇을 하는지 서로 볼 수 없다. 세션 화면의 이름표가
        // 이 값이라, 저장이 안 되면 6칸이 전부 비어 보인다.
        List<Long> members = fixtures.joinMembers(2);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, members);
        Long memberId = members.getFirst();

        assertThat(sessionService.updateGoal(memberId, sessionId,
                new SessionGoalRequest("알고리즘 3문제")).goalText()).isEqualTo("알고리즘 3문제");
        assertThat(sessionService.updateGoal(memberId, sessionId,
                new SessionGoalRequest("영어 단어 50개")).goalText()).isEqualTo("영어 단어 50개");

        assertThat(fixtures.participant(sessionId, memberId).getGoalText())
                .isEqualTo("영어 단어 50개");
    }

    @Test
    @DisplayName("끝난 세션과 남의 세션에는 오늘 할 일을 쓸 수 없다")
    void 세션_목표의_거절_경로() {
        // 이 테스트가 죽으면: 남의 세션 참가자 행을 고칠 수 있게 되거나, 끝난 세션의 기록이
        // 사후에 바뀐다. 세션 결과(SS-8)가 다시 열릴 때마다 달라진다는 뜻이다.
        List<Long> members = fixtures.joinMembers(2);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, members);
        Long stranger = fixtures.joinVerifiedMember("ss3-stranger");

        assertThatThrownBy(() -> sessionService.updateGoal(stranger, sessionId,
                new SessionGoalRequest("남의 세션")))
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_SESSION_PARTICIPANT);

        fixtures.execute("UPDATE live_session SET status = 'ENDED' WHERE id = ?", sessionId);
        assertThatThrownBy(() -> sessionService.updateGoal(members.getFirst(), sessionId,
                new SessionGoalRequest("끝난 뒤 수정")))
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_ENDED);
    }

    // ── SS-11 스티커 목록 ──

    @Test
    @DisplayName("스티커 목록은 세 종류를 한글 라벨과 함께 준다")
    void 스티커_목록() throws Exception {
        // 이 테스트가 죽으면: 세션 화면의 응원 버튼이 비거나 라벨이 코드값으로 보인다.
        // 스티커 전송은 서버를 거치지 않으므로(D17) 서버가 지는 것은 이 목록 하나뿐이다.
        Long memberId = fixtures.joinVerifiedMember("ss11-member");

        mockMvc.perform(get("/api/stickers")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + fixtures.tokenOf(memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stickers.length()").value(3))
                .andExpect(jsonPath("$.stickers[*].type")
                        .value(Matchers.containsInAnyOrder(
                                "CLAP", "MUSCLE", "FIRE")))
                .andExpect(jsonPath("$.stickers[0].label").isNotEmpty());
    }
}
