package com.morak.support;

import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.service.AuthService;
import com.morak.common.security.JwtProvider;
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
import java.time.LocalDate;
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
    private final JwtProvider jwtProvider;
    private final MemberRepository memberRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final EvictionRepository evictionRepository;
    private final ProductRepository productRepository;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public TestFixtures(AuthService authService,
                        JwtProvider jwtProvider,
                        MemberRepository memberRepository,
                        LiveSessionRepository liveSessionRepository,
                        SessionParticipantRepository sessionParticipantRepository,
                        EvictionRepository evictionRepository,
                        ProductRepository productRepository,
                        PlatformTransactionManager transactionManager,
                        JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.jwtProvider = jwtProvider;
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

    /**
     * 연령까지 확정된 회원. 개발용 소셜은 생년월일을 주지 않아 {@link #joinMember()}는 언제나
     * {@code REQUIRED}로 끝나는데, ⑤ 게이트 뒤의 API를 확인하려는 테스트는 거의 전부 그
     * 상태를 먼저 벗어나야 한다. AU-3을 부르지 않고 컬럼을 직접 쓰는 이유는 여기서 시험하려는
     * 것이 연령 확인 절차가 아니라 그 다음 API이기 때문이다.
     */
    public Long joinVerifiedMember(String authorizationCode) {
        Long memberId = joinMember(authorizationCode);
        verifyAge(memberId);
        return memberId;
    }

    public Long joinVerifiedMember() {
        return joinVerifiedMember("test-" + UUID.randomUUID());
    }

    public void verifyAge(Long memberId) {
        jdbcTemplate.update(
                "UPDATE member SET age_verification = 'VERIFIED', birth_date = ? WHERE id = ?",
                LocalDate.of(1995, 1, 1), memberId);
    }

    /**
     * 관리자 회원. 시더가 만들어 주는 관리자가 없고 승격 API도 없어서 역할을 직접 쓴다.
     * 서비스를 직접 부르는 테스트는 역할을 보지 않지만(③은 인터셉터의 검사다), HTTP로
     * {@code /api/admin/**}에 닿는 테스트에는 실제로 ADMIN인 회원이 필요하다.
     */
    public Long joinAdmin(String authorizationCode) {
        Long memberId = joinVerifiedMember(authorizationCode);
        jdbcTemplate.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", memberId);
        return memberId;
    }

    /** 인터셉터가 받아들이는 토큰. 로그인을 다시 태우지 않고 회원 번호로 바로 만든다. */
    public String tokenOf(Long memberId) {
        return jwtProvider.createToken(memberId);
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
        return createProduct(ProductType.GIFTICON, "테스트 상품", pricePoint, stock);
    }

    public Long createProduct(ProductType type, String name, int pricePoint, int stock) {
        return productRepository.save(
                Product.onSale(type, name, null, null, pricePoint, stock)).getId();
    }

    /** SR-1·SR-2가 목록과 상세에서 함께 감춰야 하는 상품. */
    public Long createHiddenProduct(int pricePoint, int stock) {
        Product product = Product.onSale(ProductType.BOOK, "감춘 상품", null, null,
                pricePoint, stock);
        product.hide();
        return productRepository.save(product).getId();
    }

    /** 품절은 감추지 않고 상태로 알린다 — 목록에는 남고 주문만 막힌다. */
    public Long createSoldOutProduct(int pricePoint) {
        Product product = Product.onSale(ProductType.GIFTICON, "품절 상품", null, null,
                pricePoint, 0);
        product.markSoldOut();
        return productRepository.save(product).getId();
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
     * 상태 컬럼 하나를 테이블에서 그대로 읽는다. 엔티티로 읽으면 영속성 컨텍스트에 남은
     * 인스턴스가 답할 수 있어서, "정말 커밋됐는가"를 보는 자리는 테이블이 답해야 한다.
     */
    public String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    /** 번호 목록. 세션 참가자처럼 준비 단계가 돌려주지 않은 식별자를 되짚을 때 쓴다. */
    public List<Long> queryLongs(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, Long.class, args);
    }

    /**
     * 정상 경로로는 만들 수 없는 중간 상태를 직접 만든다. 트랜잭션이 끊겨 남은 미결처럼
     * 서비스 호출로는 재현할 수 없는 상태에만 쓴다.
     */
    public void execute(String sql, Object... args) {
        jdbcTemplate.update(sql, args);
    }
}
