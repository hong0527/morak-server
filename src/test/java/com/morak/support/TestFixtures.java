package com.morak.support;

import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.service.AuthService;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.member.type.AgreementType;
import com.morak.member.type.SocialProvider;
import com.morak.session.entity.LiveSession;
import com.morak.session.entity.SessionParticipant;
import com.morak.session.repository.EvictionRepository;
import com.morak.session.repository.LiveSessionRepository;
import com.morak.session.repository.SessionParticipantRepository;
import com.morak.store.entity.Product;
import com.morak.store.repository.ProductRepository;
import com.morak.store.type.ProductType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테스트가 쓰는 준비 데이터와 DB 직접 조회.
 *
 * <p><b>회원은 정식 가입 경로(AU-1)로 만든다.</b> 회원 행만 넣으면 매칭이 잠글 회원 행도,
 * 웰컴 포인트 원장도 없는 계정이 되어 그 상태에서만 통과하는 게이트가 생긴다. 세션은 매칭이
 * 만드는 것과 같은 모양(방 이름 규칙·참가자 행)으로 만들되 매칭을 거치지 않는다 — 6인을
 * 모으는 일과 세션에서 일어나는 일은 다른 시험이다.
 *
 * <p>확인은 리포지터리가 아니라 SQL로도 할 수 있게 열어 둔다. "지웠다·남겼다"를 보는 자리는
 * 엔티티 매핑이 아니라 테이블이 답해야 한다.
 */
public class TestFixtures {

    private static final List<AgreementItem> MANDATORY_AGREEMENTS = List.of(
            new AgreementItem(AgreementType.TOS, true),
            new AgreementItem(AgreementType.PRIVACY, true));

    private final AuthService authService;
    private final MemberRepository memberRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final EvictionRepository evictionRepository;
    private final ProductRepository productRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public TestFixtures(AuthService authService,
                        MemberRepository memberRepository,
                        LiveSessionRepository liveSessionRepository,
                        SessionParticipantRepository sessionParticipantRepository,
                        EvictionRepository evictionRepository,
                        ProductRepository productRepository,
                        PlatformTransactionManager transactionManager,
                        JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.memberRepository = memberRepository;
        this.liveSessionRepository = liveSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.evictionRepository = evictionRepository;
        this.productRepository = productRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── 준비 ──

    public Long joinMember() {
        return joinMember("test-" + UUID.randomUUID());
    }

    /** 같은 코드로 다시 부르면 같은 소셜 계정의 재로그인이다(DevSocialClient 규약). */
    public Long joinMember(String authorizationCode) {
        authService.login(new LoginRequest(
                SocialProvider.KAKAO, authorizationCode, MANDATORY_AGREEMENTS));
        return memberRepository
                .findByProviderAndProviderUserId(SocialProvider.KAKAO, authorizationCode)
                .orElseThrow(() -> new IllegalStateException("가입한 회원을 찾지 못했다"))
                .getId();
    }

    public List<Long> joinMembers(int count) {
        List<Long> memberIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            memberIds.add(joinMember());
        }
        return memberIds;
    }

    /** 매칭이 만드는 것과 같은 모양의 진행 중 세션. 종료 예정은 {@code startedAt + targetMinutes}다. */
    public Long openSession(int targetMinutes, LocalDateTime startedAt, List<Long> memberIds) {
        return transactionTemplate.execute(status -> {
            LiveSession session = liveSessionRepository.saveAndFlush(
                    LiveSession.open(targetMinutes, startedAt,
                            startedAt.plusMinutes(targetMinutes)));
            // 방 이름은 id가 정해진 뒤에야 확정된다. 웹훅이 이 이름으로 세션을 되짚는다.
            session.assignRoomName();
            liveSessionRepository.flush();
            for (Long memberId : memberIds) {
                sessionParticipantRepository.save(
                        SessionParticipant.assign(session.getId(), memberId));
            }
            return session.getId();
        });
    }

    public Long createProduct(int pricePoint, int stock) {
        return productRepository.save(Product.onSale(ProductType.GIFTICON, "테스트 상품",
                null, null, pricePoint, stock)).getId();
    }

    // ── 확인 ──

    public Member member(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("회원이 없다: " + memberId));
    }

    public SessionParticipant participant(Long sessionId, Long memberId) {
        return sessionParticipantRepository.findBySessionIdAndMemberId(sessionId, memberId)
                .orElseThrow(() -> new IllegalStateException(
                        "참가자가 없다: session=" + sessionId + ", member=" + memberId));
    }

    /** 이의 신청(AP-1)이 필요로 하는 퇴출 번호. uk_eviction이 세션·회원 쌍의 유일성을 보장한다. */
    public Long evictionId(Long sessionId, Long memberId) {
        return evictionRepository.findBySessionIdAndMemberId(sessionId, memberId)
                .orElseThrow(() -> new IllegalStateException(
                        "퇴출이 없다: session=" + sessionId + ", member=" + memberId))
                .getId();
    }

    public LiveSession session(Long sessionId) {
        return liveSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("세션이 없다: " + sessionId));
    }

    /** 원장의 진실. 잔액 캐시와 비교하는 쪽은 언제나 이 값이다. */
    public int ledgerSum(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(delta), 0) FROM point_ledger WHERE member_id = ?",
                Integer.class, memberId);
    }

    public int count(String table, String where, Object... args) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    public int countAll(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /**
     * 정상 경로로는 만들 수 없는 중간 상태를 직접 만든다. 트랜잭션이 끊겨 남은 미결처럼
     * 서비스 호출로는 재현할 수 없는 상태에만 쓴다.
     */
    public void execute(String sql, Object... args) {
        jdbcTemplate.update(sql, args);
    }
}
