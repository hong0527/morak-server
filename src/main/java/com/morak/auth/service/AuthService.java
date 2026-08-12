package com.morak.auth.service;

import com.morak.auth.client.SocialClient;
import com.morak.auth.client.SocialUser;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.dto.response.LoginResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.common.security.JwtProvider;
import com.morak.common.security.SocialHasher;
import com.morak.match.entity.MatchLock;
import com.morak.match.repository.MatchLockRepository;
import com.morak.member.entity.Member;
import com.morak.member.repository.BlockedSocialHashRepository;
import com.morak.member.repository.MemberRepository;
import com.morak.member.service.NicknameGenerator;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.MemberStatus;
import com.morak.member.type.SocialProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 소셜 로그인·가입 (API명세서 AU-1).
 */
@Service
public class AuthService {

    /** 만 나이 하한. 명세는 "만 14세 검증"만 있고 별도 숫자 정의처가 없어 여기서 정의한다. */
    private static final int MINIMUM_AGE = 14;

    private static final String LOGIN_NORMAL = "NORMAL";
    private static final String LOGIN_RESTORED = "RESTORED";

    private final SocialClient socialClient;
    private final SocialHasher socialHasher;
    private final BlockedSocialHashRepository blockedSocialHashRepository;
    private final MemberRepository memberRepository;
    private final MatchLockRepository matchLockRepository;
    private final NicknameGenerator nicknameGenerator;
    private final JwtProvider jwtProvider;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AuthService(SocialClient socialClient,
                       SocialHasher socialHasher,
                       BlockedSocialHashRepository blockedSocialHashRepository,
                       MemberRepository memberRepository,
                       MatchLockRepository matchLockRepository,
                       NicknameGenerator nicknameGenerator,
                       JwtProvider jwtProvider,
                       Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.socialClient = socialClient;
        this.socialHasher = socialHasher;
        this.blockedSocialHashRepository = blockedSocialHashRepository;
        this.memberRepository = memberRepository;
        this.matchLockRepository = matchLockRepository;
        this.nicknameGenerator = nicknameGenerator;
        this.jwtProvider = jwtProvider;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public LoginResponse login(LoginRequest request) {
        // 소셜 서버 호출은 느릴 수 있어 트랜잭션(DB 커넥션) 밖에서 한다.
        // @Transactional을 나눠 쓰면 같은 빈 안의 자기 호출이라 프록시를 타지 않으므로
        // 프로그래밍 방식 트랜잭션으로 경계를 직접 긋는다.
        SocialUser socialUser = socialClient.fetch(request.provider(), request.authorizationCode());
        try {
            return transactionTemplate.execute(
                    status -> loginInternal(request.provider(), socialUser));
        } catch (DataIntegrityViolationException e) {
            // 같은 소셜 계정의 첫 로그인 두 건이 동시에 오면 한쪽이 uk_member_provider에
            // 걸린다. 상대가 이미 가입시킨 것이므로 새 트랜잭션에서 기존 회원 로그인으로
            // 1회 재시도한다. 재조회에도 없으면 다른 제약 위반이라 그대로 던진다.
            if (memberRepository.findByProviderAndProviderUserId(
                    request.provider(), socialUser.providerUserId()).isEmpty()) {
                throw e;
            }
            return transactionTemplate.execute(
                    status -> loginInternal(request.provider(), socialUser));
        }
    }

    private LoginResponse loginInternal(SocialProvider provider, SocialUser socialUser) {
        LocalDateTime now = LocalDateTime.now(clock);
        rejectIfRejoinBlocked(provider, socialUser.providerUserId(), now);

        Member member = memberRepository
                .findByProviderAndProviderUserId(provider, socialUser.providerUserId())
                .orElse(null);
        boolean isNewMember = member == null;
        String loginResult = LOGIN_NORMAL;

        if (member == null) {
            member = join(provider, socialUser, now);
        } else if (member.getStatus() == MemberStatus.WITHDRAW_PENDING) {
            // 삭제 예정 시각이 지났으면 B4 배치 실행 전이라도 삭제된 계정으로 본다.
            // MemberService.cancelWithdrawal과 같은 규칙 — 여기만 느슨하면 로그인으로 계정이 부활한다.
            if (!member.getDeleteScheduledAt().isAfter(now)) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            member.cancelWithdrawal();
            loginResult = LOGIN_RESTORED;
        }

        verifyAgeIfProvided(member, socialUser.birthDate());

        String accessToken = jwtProvider.createToken(member.getId());
        return LoginResponse.from(accessToken, isNewMember, member, loginResult);
    }

    private void rejectIfRejoinBlocked(SocialProvider provider, String providerUserId,
                                       LocalDateTime now) {
        String hash = socialHasher.hash(provider, providerUserId);
        boolean blocked = blockedSocialHashRepository.findById(hash)
                .map(entry -> entry.isEffective(now))
                .orElse(false);
        if (blocked) {
            throw new BusinessException(ErrorCode.REJOIN_BLOCKED);
        }
    }

    private Member join(SocialProvider provider, SocialUser socialUser, LocalDateTime now) {
        Member member = memberRepository.save(Member.join(
                provider,
                socialUser.providerUserId(),
                nicknameGenerator.generate(),
                socialUser.nickname(),
                socialUser.profileImageUrl(),
                now));
        // 매칭은 회원 단위 행 잠금으로 직렬화하는데 행이 없으면 잠글 수 없다.
        // 그래서 가입과 같은 트랜잭션에서 잠금 행을 함께 만든다(MatchLock 주석 참조).
        matchLockRepository.save(MatchLock.forMember(member.getId()));
        return member;
    }

    /**
     * 소셜 계정이 생년월일을 줬으면 그 자리에서 검증한다. 못 받았으면 REQUIRED로 남아
     * AU-3에서 입력받는다.
     */
    private void verifyAgeIfProvided(Member member, LocalDate birthDate) {
        // UNDER_AGE·VERIFIED 확정 후에는 재검증으로 뒤집을 수 없다(AU-3과 같은 규칙)
        if (birthDate == null || member.getAgeVerification() != AgeVerification.REQUIRED) {
            return;
        }
        int age = Period.between(birthDate, LocalDate.now(clock)).getYears();
        AgeVerification result = age >= MINIMUM_AGE
                ? AgeVerification.VERIFIED
                : AgeVerification.UNDER_AGE;
        member.verifyAge(birthDate, result);
    }
}
