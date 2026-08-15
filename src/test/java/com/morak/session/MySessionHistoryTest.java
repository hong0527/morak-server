package com.morak.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.morak.common.dto.PageResponse;
import com.morak.session.dto.response.MySessionSummaryResponse;
import com.morak.session.service.SessionClosingBatch;
import com.morak.session.service.SessionExitService;
import com.morak.session.service.SessionService;
import com.morak.session.type.LeftReason;
import com.morak.session.type.ParticipantStatus;
import com.morak.session.type.SessionStatus;
import com.morak.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * SS-9 내 세션 이력.
 *
 * <p>지금까지 이 API에 대해 확인된 것은 "연령 미확인 상태에서도 열린다"는 게이트 예외뿐이었다
 * ({@code GateMatrixTest}). 정작 <b>목록에 무엇이 담기는가</b>는 아무도 보지 않았다.
 *
 * <p>이 화면은 사용자가 자기 기록을 확인하는 유일한 자리다. 완주 여부와 지급 포인트가 여기
 * 실리므로, 값이 틀리면 "포인트를 못 받았다"는 문의가 곧바로 들어온다 — 그런데 진짜 지급은
 * 정상일 수 있어서 원인을 찾는 데 시간이 걸린다.
 */
@DisplayName("내 세션 이력")
class MySessionHistoryTest extends IntegrationTest {

    private static final int TARGET_MINUTES = 60;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionExitService sessionExitService;

    @Autowired
    private SessionClosingBatch sessionClosingBatch;

    @Value("${morak.point.session-complete-per-hour}")
    private int completePointPerHour;

    @Test
    @DisplayName("이력은 최신 세션부터 실리고 남의 세션은 섞이지 않는다")
    void 본인_세션만_최신순으로_실린다() {
        // 이 테스트가 죽으면: 남의 세션이 내 이력에 보이거나(다른 사람이 언제 공부했는지가
        // 새어 나간다), 방금 끝난 세션이 목록 아래로 밀려 사용자가 찾지 못한다.
        Long mine = fixtures.joinVerifiedMember("ss9-mine");
        Long other = fixtures.joinVerifiedMember("ss9-other");
        Long older = close(BASE_TIME, List.of(mine, other));
        Long newer = close(BASE_TIME.plusHours(3), List.of(mine, other));
        // 내가 끼지 않은 세션
        close(BASE_TIME.plusHours(6), List.of(other, fixtures.joinVerifiedMember("ss9-third")));

        PageResponse<MySessionSummaryResponse> history =
                sessionService.getMySessions(mine, null, null, null);

        assertThat(history.totalElements()).isEqualTo(2);
        assertThat(history.content())
                .extracting(MySessionSummaryResponse::sessionId)
                .containsExactly(newer, older);
    }

    @Test
    @DisplayName("완주한 세션은 완주 표시와 지급 포인트를 함께 싣는다")
    void 완주_세션의_값() {
        // 이 테스트가 죽으면: 완주하고도 이력에 미완주로 보이거나 지급액이 0으로 보인다.
        // 지급 자체는 정상인데 화면만 틀린 경우라 원인을 찾기 어렵다.
        Long memberId = fixtures.joinVerifiedMember("ss9-completed");
        Long sessionId = close(BASE_TIME, List.of(memberId, fixtures.joinVerifiedMember()));

        MySessionSummaryResponse summary =
                sessionService.getMySessions(memberId, null, null, null).content().getFirst();

        assertThat(summary.sessionId()).isEqualTo(sessionId);
        assertThat(summary.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(summary.participantStatus()).isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(summary.completed()).isTrue();
        assertThat(summary.targetMinutes()).isEqualTo(TARGET_MINUTES);
        assertThat(summary.pointAwarded())
                .isEqualTo(completePointPerHour * TARGET_MINUTES / 60);
        assertThat(summary.warningCount()).isZero();
        assertThat(summary.endedAt()).isNotNull();
    }

    @Test
    @DisplayName("도중에 나간 세션은 미완주로 남고 포인트가 없다")
    void 퇴장_세션의_값() {
        // 이 테스트가 죽으면: 나간 세션이 완주로 집계돼 포인트가 지급된 것처럼 보인다.
        // 완주 판정은 종료 시각의 참가자 상태가 기준이다(★D1).
        Long memberId = fixtures.joinVerifiedMember("ss9-left");
        List<Long> members = List.of(memberId, fixtures.joinVerifiedMember(),
                fixtures.joinVerifiedMember());
        clock.fixAt(BASE_TIME);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, BASE_TIME, members);
        clock.fixAt(BASE_TIME.plusMinutes(10));
        sessionExitService.leaveOnRequest(memberId, sessionId, LeftReason.PERSONAL);
        clock.fixAt(BASE_TIME.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();

        MySessionSummaryResponse summary =
                sessionService.getMySessions(memberId, null, null, null).content().getFirst();

        assertThat(summary.participantStatus()).isEqualTo(ParticipantStatus.LEFT);
        assertThat(summary.completed()).isFalse();
        assertThat(summary.pointAwarded()).isZero();
    }

    @Test
    @DisplayName("상태 필터를 주면 그 상태의 세션만 남고, 생략하면 전체다")
    void 상태_필터() {
        // 이 테스트가 죽으면: 진행 중인 세션과 끝난 세션을 가려 볼 수 없다. 필터가 조건을
        // 만들지 못하고 통째로 무시되면 화면의 탭이 전부 같은 목록을 그린다.
        Long memberId = fixtures.joinVerifiedMember("ss9-filter");
        Long ended = close(BASE_TIME, List.of(memberId, fixtures.joinVerifiedMember()));
        clock.fixAt(BASE_TIME.plusHours(3));
        Long live = fixtures.openSession(TARGET_MINUTES, BASE_TIME.plusHours(3),
                List.of(memberId, fixtures.joinVerifiedMember()));

        assertThat(sessionService.getMySessions(memberId, SessionStatus.ENDED, null, null)
                .content())
                .extracting(MySessionSummaryResponse::sessionId)
                .containsExactly(ended);
        assertThat(sessionService.getMySessions(memberId, SessionStatus.LIVE, null, null)
                .content())
                .extracting(MySessionSummaryResponse::sessionId)
                .containsExactly(live);
        assertThat(sessionService.getMySessions(memberId, null, null, null).totalElements())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("이력이 없으면 빈 목록이지 오류가 아니다")
    void 이력이_없으면_빈_목록이다() {
        // 이 테스트가 죽으면: 가입 직후 이력 화면이 오류로 뜬다. 아직 아무것도 안 한 사용자가
        // 가장 먼저 보게 되는 화면이라 여기가 깨지면 첫인상이 장애다.
        Long memberId = fixtures.joinVerifiedMember("ss9-empty");

        PageResponse<MySessionSummaryResponse> history =
                sessionService.getMySessions(memberId, null, null, null);

        assertThat(history.content()).isEmpty();
        assertThat(history.totalElements()).isZero();
        assertThat(history.totalPages()).isZero();
    }

    /** 세션을 열고 정시 종료까지 밀어 닫는다. 반환은 세션 번호다. */
    private Long close(LocalDateTime startedAt, List<Long> memberIds) {
        clock.fixAt(startedAt);
        Long sessionId = fixtures.openSession(TARGET_MINUTES, startedAt, memberIds);
        clock.fixAt(startedAt.plusMinutes(TARGET_MINUTES + 1));
        sessionClosingBatch.run();
        return sessionId;
    }
}
