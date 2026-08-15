package com.morak.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.morak.member.service.MemberService;
import com.morak.report.dto.request.SanctionCommand;
import com.morak.report.service.SanctionService;
import com.morak.report.type.SanctionType;
import com.morak.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 전역 인터셉터의 게이트 매트릭스(API명세서 §0-3).
 *
 * <p><b>여기가 이 서버에서 유일하게 "누가 무엇을 할 수 있는가"를 정하는 자리다.</b> 개별
 * 서비스는 권한을 다시 보지 않으므로, 이 표가 한 줄 틀리면 그 API는 어떤 서비스 테스트로도
 * 잡히지 않는 상태로 열리거나 닫힌다. 나머지 테스트가 전부 서비스를 직접 부르는 것도 그
 * 이유다 — 게이트를 지나는 경로는 HTTP뿐이라 여기서만 확인할 수 있다.
 *
 * <p>확인하는 것은 두 방향이다. <b>막아야 할 것을 막는가</b>(제재·탈퇴 유예·연령 미확인·비관리자)와
 * <b>열어 둬야 할 것을 여는가</b>(구제 경로와 안전 도구의 예외). 뒤쪽이 더 중요하다 — 잘못
 * 닫힌 예외는 사고가 나기 전까지 아무도 모르고, 그때는 이미 이의 기한 3일이 지나 있다.
 */
@DisplayName("인터셉터 게이트 매트릭스")
class GateMatrixTest extends IntegrationTest {

    private static final Long ADMIN_ACTOR = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SanctionService sanctionService;

    @Autowired
    private MemberService memberService;

    // ── ④ 제재 게이트 ──

    @Test
    @DisplayName("제재 중에는 참여도 조회도 막히지만 구제 경로 넷은 열려 있다")
    void 제재_게이트의_예외_넷이_열려_있다() throws Exception {
        // 이 테스트가 죽으면: 제재당한 회원이 갇힌 것이다. AP-1·AP-2가 닫히면 잘못된 퇴출을
        // 되돌릴 유일한 수단이 사라지고(NFR-402), AU-5가 닫히면 탈퇴 철회가 영구 불가해진다.
        // 반대로 PT-1·MT-1이 열려 있으면 제재가 아무것도 제한하지 못한 것이다.
        Long memberId = fixtures.joinVerifiedMember("gate-sanctioned");
        sanctionService.apply(memberId, null, new SanctionCommand(SanctionType.TEMP, 3),
                ADMIN_ACTOR);
        String token = fixtures.tokenOf(memberId);

        // 막힌다 — 예외 열에 없는 것은 참여든 조회든 전부 닫힌다
        as(post("/api/match-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetMinutes\":60}"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_SANCTIONED"));
        as(get("/api/members/me/points"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_SANCTIONED"));
        as(get("/api/store/products"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_SANCTIONED"));

        // 열린다 — AU-2 자기 상태 확인, AP-2 이의 결과 확인
        as(get("/api/members/me"), token).andExpect(status().isOk());
        as(get("/api/members/me/appeals"), token).andExpect(status().isOk());

        // 열린다 — AU-4 탈퇴 신청. 상태를 바꾸므로 이 검사가 마지막이다
        as(post("/api/members/me/withdrawal"), token).andExpect(status().isAccepted());
        // 열린다 — AU-5 탈퇴 철회. ②와 ④를 동시에 지고 있는 유일한 자리다
        as(delete("/api/members/me/withdrawal"), token).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("기간 제재는 종료 시각을, 영구 제재는 null을 details로 알린다")
    void 제재_응답이_종료_시각을_싣는다() throws Exception {
        // 이 테스트가 죽으면: 제재당한 사용자에게 "언제 풀리는지"를 알릴 방법이 사라진다.
        // 영구 제재에서 endsAt 키 자체가 빠지면 프론트가 "곧 풀린다"로 잘못 그린다.
        Long temp = fixtures.joinVerifiedMember("gate-temp");
        sanctionService.apply(temp, null, new SanctionCommand(SanctionType.TEMP, 3), ADMIN_ACTOR);
        as(get("/api/members/me/points"), fixtures.tokenOf(temp))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.endsAt").exists());

        Long permanent = fixtures.joinVerifiedMember("gate-permanent");
        sanctionService.apply(permanent, null, new SanctionCommand(SanctionType.PERMANENT, null),
                ADMIN_ACTOR);
        as(get("/api/members/me/points"), fixtures.tokenOf(permanent))
                .andExpect(status().isForbidden())
                // 키는 있고 값만 null이다. exists()는 null 값에서 실패하므로 키 유무를 직접 본다
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(jsonPath("$.error.details.endsAt").doesNotExist());
    }

    @Test
    @DisplayName("기간이 끝난 제재는 더 이상 막지 않는다")
    void 만료된_제재는_통과시킨다() throws Exception {
        // 이 테스트가 죽으면: 제재가 영원히 풀리지 않거나(유효 판정이 종료 시각을 보지 않음),
        // 시작 전 제재가 미리 발동한다. 어느 쪽이든 판정이 요청 시점 DB 값이 아니게 된 것이다.
        Long memberId = fixtures.joinVerifiedMember("gate-expired");
        sanctionService.apply(memberId, null, new SanctionCommand(SanctionType.TEMP, 3),
                ADMIN_ACTOR);
        as(get("/api/members/me/points"), fixtures.tokenOf(memberId))
                .andExpect(status().isForbidden());

        clock.fixAt(now().plusDays(3).plusMinutes(1));

        // 토큰을 다시 발급받는다. 제재 3일은 토큰 수명 24시간보다 길어서 앞의 토큰을 그대로
        // 쓰면 여기서 보는 것이 ④ 해제가 아니라 ① 만료가 된다 — 사용자도 그 사이 재로그인한다
        as(get("/api/members/me/points"), fixtures.tokenOf(memberId))
                .andExpect(status().isOk());
    }

    // ── ② 회원 상태 게이트 ──

    @Test
    @DisplayName("탈퇴 유예 중에는 조회 전용 API까지 닫히고 자기 확인과 철회만 남는다")
    void 탈퇴_유예는_조회도_닫는다() throws Exception {
        // 이 테스트가 죽으면: ② 게이트가 "참여 API만 막는다"로 좁혀진 것이다. 유예는 곧
        // 사라질 계정이라 소비와 참여를 가르지 않는데, PT-1·SR-1이 열리면 탈퇴 신청한 계정이
        // 포인트를 계속 쓴다.
        Long memberId = fixtures.joinVerifiedMember("gate-withdrawing");
        String token = fixtures.tokenOf(memberId);
        memberService.requestWithdrawal(memberId);

        as(get("/api/members/me/points"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WITHDRAWAL_PENDING"));
        as(get("/api/store/products"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WITHDRAWAL_PENDING"));
        // SS-9는 ⑤ 연령만 면제받는다. ②는 그대로 진다
        as(get("/api/members/me/sessions"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WITHDRAWAL_PENDING"));

        as(get("/api/members/me"), token).andExpect(status().isOk());
        as(delete("/api/members/me/withdrawal"), token).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("파기된 계정의 토큰은 유예 안내가 아니라 인증 실패다")
    void 삭제된_계정은_401이다() throws Exception {
        // 이 테스트가 죽으면: 파기된 계정에 403 WITHDRAWAL_PENDING이 나가 "아직 되돌릴 수
        // 있다"고 안내하게 된다. 서명이 유효한 토큰은 파기 후 최대 24시간 살아 있다.
        Long memberId = fixtures.joinVerifiedMember("gate-deleted");
        String token = fixtures.tokenOf(memberId);
        fixtures.execute("UPDATE member SET status = 'DELETED' WHERE id = ?", memberId);

        as(get("/api/members/me"), token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ── ⑤ 연령 게이트 ──

    @Test
    @DisplayName("연령 미확인은 참여를 막되 이력 조회와 생년월일 입력은 막지 않는다")
    void 연령_게이트의_예외가_열려_있다() throws Exception {
        // 이 테스트가 죽으면: 가입 직후 화면에서 빠져나올 수 없다. AU-3이 ⑤에 걸리면 연령을
        // 입력하러 가는 API 자체가 연령 미확인으로 막혀 계정이 영구히 잠긴다.
        Long memberId = fixtures.joinMember("gate-unverified");
        String token = fixtures.tokenOf(memberId);

        as(post("/api/match-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetMinutes\":60}"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AGE_NOT_VERIFIED"));
        as(get("/api/store/products"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AGE_NOT_VERIFIED"));

        // SS-9: 본인 상태 확인은 막지 않는다. 참여는 MT-1에서 이미 막혔다
        as(get("/api/members/me/sessions"), token).andExpect(status().isOk());
        // AU-3: 여기가 닫히면 나갈 문이 없다
        as(post("/api/members/me/birthdate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"birthDate\":\"1995-01-01\"}"), token)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("연령 미확인이어도 신고는 접수된다")
    void 안전_도구는_연령으로_막지_않는다() throws Exception {
        // 이 테스트가 죽으면: 생년월일을 아직 넣지 않은 사용자가 유해 상황을 보고도 신고할
        // 수 없다. 안전 도구를 게이트로 막지 않는다는 판단(§0-3)이 이 한 줄에 걸려 있다.
        Long reporter = fixtures.joinMember("gate-reporter");
        Long target = fixtures.joinMember("gate-reported");
        Long sessionId = fixtures.openSession(60, BASE_TIME, List.of(reporter, target));

        as(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"targetType":"MEMBER","targetMemberId":%d,"sessionId":%d,
                         "reasonCode":"SEXUAL_CONTENT","detail":"화면에 부적절한 내용"}
                        """.formatted(target, sessionId)), fixtures.tokenOf(reporter))
                .andExpect(status().isCreated());
    }

    // ── ③ 관리자 역할 ──

    @Test
    @DisplayName("관리자 API는 역할로 갈리고, 관리자에게는 제재·연령을 묻지 않는다")
    void 관리자_역할_게이트() throws Exception {
        // 이 테스트가 죽으면: 일반 회원이 신고 케이스 전체를 열람하게 되거나(역할 검사 누락),
        // 관리자가 자기 계정의 연령 미확인 때문에 운영 화면에 들어가지 못한다.
        Long participant = fixtures.joinVerifiedMember("gate-participant");
        as(get("/api/admin/reports"), fixtures.tokenOf(participant))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));

        Long admin = fixtures.joinAdmin("gate-admin");
        // 관리자에게 ④⑤는 걸지 않는다 — 연령 미확인 상태로 되돌려도 통과해야 한다
        fixtures.execute("UPDATE member SET age_verification = 'REQUIRED' WHERE id = ?", admin);
        as(get("/api/admin/reports"), fixtures.tokenOf(admin)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("탈퇴 유예 중인 관리자는 관리자 API를 쓸 수 없다")
    void 관리자도_회원_상태는_진다() throws Exception {
        // 이 테스트가 죽으면: ② 게이트의 예외에 /api/admin/**이 잘못 들어간 것이다. 관리자에게
        // 면제되는 것은 ④⑤뿐이고, 탈퇴 신청한 계정은 역할과 무관하게 닫혀야 한다.
        Long admin = fixtures.joinAdmin("gate-admin-withdrawing");
        String token = fixtures.tokenOf(admin);
        memberService.requestWithdrawal(admin);

        as(get("/api/admin/reports"), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WITHDRAWAL_PENDING"));
    }

    // ── ① JWT ──

    @Test
    @DisplayName("헤더가 없거나 서명이 깨졌거나 없는 회원을 가리키면 전부 401이다")
    void JWT_게이트() throws Exception {
        // 이 테스트가 죽으면: 인증 없이 API가 열렸거나, 서명이 유효하지만 사라진 회원을
        // 가리키는 토큰이 통과해 뒤쪽 코드가 없는 회원으로 500을 낸다.
        mockMvc.perform(get("/api/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/members/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        // Bearer 접두사가 없으면 값이 무엇이든 인증 실패다
        Long memberId = fixtures.joinVerifiedMember("gate-jwt");
        mockMvc.perform(get("/api/members/me")
                        .header(HttpHeaders.AUTHORIZATION, fixtures.tokenOf(memberId)))
                .andExpect(status().isUnauthorized());

        // 서명은 맞는데 회원이 없는 경우
        as(get("/api/members/me"), fixtures.tokenOf(999_999L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("웹훅 2종은 JWT 없이 닿고 서명으로만 갈린다")
    void 웹훅은_JWT를_지나지_않는다() throws Exception {
        // 이 테스트가 죽으면: 웹훅이 ① 게이트에 걸려 UNAUTHORIZED로 떨어진다. 외부 서버는
        // 우리 JWT를 가질 수 없으므로 결제 승인과 세션 종료 통보가 통째로 유실된다.
        // 여기서 보는 것은 "게이트를 지나 컨트롤러의 서명 검증까지 도달했는가"다 —
        // 서명이 없어 401이지만 코드가 INVALID_WEBHOOK_SIGNATURE라는 사실이 그 증거다.
        mockMvc.perform(post("/api/webhooks/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"PAYMENT_STATUS_CHANGED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_WEBHOOK_SIGNATURE"));

        mockMvc.perform(post("/api/webhooks/livekit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"room_finished\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_WEBHOOK_SIGNATURE"));
    }

    private ResultActions as(MockHttpServletRequestBuilder request, String token)
            throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
