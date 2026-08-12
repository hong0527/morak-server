package com.morak.common.security;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.MemberRole;
import com.morak.member.type.MemberStatus;
import com.morak.report.entity.Sanction;
import com.morak.report.repository.SanctionRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 전역 인증 검문소 (API명세서 §0-2). 검사 순서는 ① JWT → ② 회원 상태 → ③ 관리자 역할
 * → ④ 유효 제재 → ⑤ 연령 확인.
 *
 * <p>모든 검사는 JWT 클레임이 아니라 요청 시점의 DB 현재값으로 한다. 토큰이 24시간 유효하므로
 * 클레임을 믿으면 그 사이 걸린 제재·탈퇴가 최대 하루 동안 무시된다.
 *
 * <p>경로별 예외는 {@link #SKIP_RULES} 표 한 곳에만 모은다. 우회 조건이 코드 곳곳에 흩어지면
 * API가 추가될 때 반드시 빠뜨린다.
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 인터셉터가 확인한 회원 번호를 담는 request attribute. LoginMemberArgumentResolver가 꺼낸다. */
    public static final String MEMBER_ID_ATTRIBUTE = "morak.loginMemberId";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final PathPatternParser PARSER = new PathPatternParser();
    private static final PathPattern ADMIN_PATTERN = PARSER.parse("/api/admin/**");

    /**
     * §0-2의 검사 항목. ③(관리자 역할)은 건너뛸 수 있는 검사가 아니라
     * {@code /api/admin/**}에만 걸리는 추가 검사라 표에 넣지 않는다.
     */
    private enum Gate {JWT, MEMBER_STATUS, SANCTION, AGE}

    /**
     * 엔드포인트 × 게이트 매트릭스(§0-3)에서 검사를 건너뛰는 행만 모은 표.
     * API가 추가되거나 예외가 바뀌면 이 표만 고친다. 여기 없는 경로는 ②④⑤를 전부 검사한다.
     * 한 요청에 여러 행이 걸리면 건너뛰기의 합집합을 적용한다.
     */
    private static final List<SkipRule> SKIP_RULES = List.of(
            // AU-2: 제재 중·탈퇴 대기 중에도 자기 상태는 확인할 수 있어야 한다
            rule("GET", "/api/members/me", Set.of(Gate.MEMBER_STATUS, Gate.SANCTION, Gate.AGE)),
            // AU-3: 연령 미확인 상태에서 호출하는 API다. ⑤로 막으면 그 화면에서 빠져나올 수 없다
            rule("POST", "/api/members/me/birthdate", Set.of(Gate.AGE)),
            // AU-4: 제재 중이어도 탈퇴는 할 수 있어야 한다
            rule("POST", "/api/members/me/withdrawal", Set.of(Gate.SANCTION, Gate.AGE)),
            // AU-5: 철회를 막으면 계정 복구가 영구 불가해진다
            rule("DELETE", "/api/members/me/withdrawal",
                    Set.of(Gate.MEMBER_STATUS, Gate.SANCTION, Gate.AGE)),
            // AU-6: 캠 분석 동의는 세션 진입 전 단계라 연령 확인 전에도 받을 수 있다
            rule("POST", "/api/members/me/media-consent", Set.of(Gate.AGE)),
            // SS-9: 내 세션 이력 확인은 연령과 무관하게 허용한다 (참여는 MT-1이 막는다)
            rule("GET", "/api/members/me/sessions", Set.of(Gate.AGE)),
            // RP-1: 안전 도구는 절대 막지 않는다 — 미성년이 유해물을 보고도 신고 못 하는 상태 방지
            rule("POST", "/api/reports", Set.of(Gate.AGE)),
            // AP-1: 퇴출 이의는 잘못된 퇴출을 되돌리는 유일한 수단이다(NFR-402). ④로 막으면
            // 별개의 제재가 구제 경로까지 함께 닫아 3일짜리 이의 기한이 그대로 지나간다.
            // AU-5 철회를 열어 두는 것과 같은 논리다
            rule("POST", "/api/evictions/*/appeals", Set.of(Gate.SANCTION)),
            // SS-10·PY-3: 호출 주체가 회원이 아니라 외부 서버다. 회원 상태·제재·연령 검사가
            // 성립하지 않으므로 전 게이트를 건너뛰고, 신원 보장은 각 컨트롤러의 서명 검증이 진다
            rule("POST", "/api/webhooks/livekit", EnumSet.allOf(Gate.class)),
            rule("POST", "/api/webhooks/payment", EnumSet.allOf(Gate.class)),
            // AD-1~8: 관리자에게 ④⑤는 걸지 않는다. 대신 ③ 역할 검사를 통과해야 한다
            rule(null, "/api/admin/**", Set.of(Gate.SANCTION, Gate.AGE)));

    private final JwtProvider jwtProvider;
    private final MemberRepository memberRepository;
    private final SanctionRepository sanctionRepository;
    private final Clock clock;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        // 정적 리소스 등 컨트롤러가 아닌 핸들러는 검사 대상이 아니다
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        Set<Gate> skips = skipsFor(request);
        if (skips.contains(Gate.JWT)) {
            return true;
        }

        Long memberId = parseMemberId(request);
        // 존재하지 않는 회원 번호의 토큰은 서명이 맞아도 인증 실패로 본다
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        checkMemberStatus(member, skips);
        checkAdminRole(request, member);
        checkSanction(member, skips);
        checkAge(member, skips);

        request.setAttribute(MEMBER_ID_ATTRIBUTE, member.getId());
        return true;
    }

    private Long parseMemberId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return jwtProvider.parseMemberId(header.substring(BEARER_PREFIX.length()));
        } catch (ExpiredJwtException e) {
            // 만료는 별도 코드로 내려야 클라이언트가 재로그인 안내를 띄울 수 있다
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private void checkMemberStatus(Member member, Set<Gate> skips) {
        if (skips.contains(Gate.MEMBER_STATUS)) {
            return;
        }
        if (member.getStatus() == MemberStatus.DELETED) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (member.getStatus() == MemberStatus.WITHDRAW_PENDING) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_PENDING);
        }
    }

    private void checkAdminRole(HttpServletRequest request, Member member) {
        if (ADMIN_PATTERN.matches(PathContainer.parsePath(request.getRequestURI()))
                && member.getRole() != MemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ROLE);
        }
    }

    private void checkSanction(Member member, Set<Gate> skips) {
        if (skips.contains(Gate.SANCTION)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Sanction> effective = sanctionRepository.findByMemberId(member.getId()).stream()
                .filter(sanction -> sanction.isEffectiveAt(now))
                .toList();
        if (effective.isEmpty()) {
            return;
        }
        // 영구 제재가 섞여 있으면 종료 시각이 없고, 기간형만 있으면 가장 늦게 끝나는 시각을 알린다
        LocalDateTime endsAt = Sanction.latestEndsAt(effective);
        // Map.of는 null 값을 못 담는다 (영구 제재는 endsAt이 null)
        Map<String, Object> details = new HashMap<>();
        details.put("endsAt", endsAt);
        throw new BusinessException(ErrorCode.MEMBER_SANCTIONED, details);
    }

    private void checkAge(Member member, Set<Gate> skips) {
        if (skips.contains(Gate.AGE)) {
            return;
        }
        if (member.getAgeVerification() != AgeVerification.VERIFIED) {
            throw new BusinessException(ErrorCode.AGE_NOT_VERIFIED);
        }
    }

    private Set<Gate> skipsFor(HttpServletRequest request) {
        Set<Gate> skips = EnumSet.noneOf(Gate.class);
        for (SkipRule skipRule : SKIP_RULES) {
            if (skipRule.matches(request)) {
                skips.addAll(skipRule.skips());
            }
        }
        return skips;
    }

    private static SkipRule rule(String method, String pattern, Set<Gate> skips) {
        return new SkipRule(method, PARSER.parse(pattern), skips);
    }

    /** method가 null이면 모든 메서드에 적용한다. */
    private record SkipRule(String method, PathPattern pattern, Set<Gate> skips) {

        boolean matches(HttpServletRequest request) {
            return (method == null || method.equals(request.getMethod()))
                    && pattern.matches(PathContainer.parsePath(request.getRequestURI()));
        }
    }
}
