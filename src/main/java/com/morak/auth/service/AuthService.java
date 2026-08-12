package com.morak.auth.service;

import com.morak.auth.client.SocialClient;
import com.morak.auth.client.SocialUser;
import com.morak.auth.dto.request.AgreementItem;
import com.morak.auth.dto.request.LoginRequest;
import com.morak.auth.dto.response.LoginResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.common.security.JwtProvider;
import com.morak.common.security.SocialHasher;
import com.morak.match.entity.MatchLock;
import com.morak.match.repository.MatchLockRepository;
import com.morak.member.entity.Member;
import com.morak.member.entity.MemberAgreement;
import com.morak.member.repository.BlockedSocialHashRepository;
import com.morak.member.repository.MemberAgreementRepository;
import com.morak.member.repository.MemberRepository;
import com.morak.member.service.MemberAccountPurger;
import com.morak.member.service.NicknameGenerator;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.AgreementType;
import com.morak.member.type.MemberStatus;
import com.morak.member.type.SocialProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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

    /** 이 둘이 없으면 가입이 성립하지 않는다. MARKETING은 선택이라 여기 없다. */
    private static final Set<AgreementType> MANDATORY_AGREEMENTS =
            Set.of(AgreementType.TOS, AgreementType.PRIVACY);

    private static final String LOGIN_NORMAL = "NORMAL";
    private static final String LOGIN_RESTORED = "RESTORED";

    private final SocialClient socialClient;
    private final SocialHasher socialHasher;
    private final BlockedSocialHashRepository blockedSocialHashRepository;
    private final MemberRepository memberRepository;
    private final MemberAgreementRepository memberAgreementRepository;
    private final MatchLockRepository matchLockRepository;
    private final MemberAccountPurger memberAccountPurger;
    private final NicknameGenerator nicknameGenerator;
    private final JwtProvider jwtProvider;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AuthService(SocialClient socialClient,
                       SocialHasher socialHasher,
                       BlockedSocialHashRepository blockedSocialHashRepository,
                       MemberRepository memberRepository,
                       MemberAgreementRepository memberAgreementRepository,
                       MatchLockRepository matchLockRepository,
                       MemberAccountPurger memberAccountPurger,
                       NicknameGenerator nicknameGenerator,
                       JwtProvider jwtProvider,
                       Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.socialClient = socialClient;
        this.socialHasher = socialHasher;
        this.blockedSocialHashRepository = blockedSocialHashRepository;
        this.memberRepository = memberRepository;
        this.memberAgreementRepository = memberAgreementRepository;
        this.matchLockRepository = matchLockRepository;
        this.memberAccountPurger = memberAccountPurger;
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
            return transactionTemplate.execute(status -> loginInternal(request, socialUser));
        } catch (DataIntegrityViolationException e) {
            // 같은 소셜 계정의 첫 로그인 두 건이 동시에 오면 한쪽이 uk_member_provider에
            // 걸린다. 상대가 이미 가입시킨 것이므로 새 트랜잭션에서 기존 회원 로그인으로
            // 1회 재시도한다. 재조회에도 없으면 다른 제약 위반이라 그대로 던진다.
            if (memberRepository.findByProviderAndProviderUserId(
                    request.provider(), socialUser.providerUserId()).isEmpty()) {
                throw e;
            }
            return transactionTemplate.execute(status -> loginInternal(request, socialUser));
        }
    }

    private LoginResponse loginInternal(LoginRequest request, SocialUser socialUser) {
        SocialProvider provider = request.provider();
        LocalDateTime now = LocalDateTime.now(clock);
        rejectIfRejoinBlocked(provider, socialUser.providerUserId(), now);

        Member member = memberRepository
                .findByProviderAndProviderUserId(provider, socialUser.providerUserId())
                .orElse(null);
        boolean isNewMember = member == null;
        String loginResult = LOGIN_NORMAL;

        if (member == null) {
            // 미만 판정을 가입보다 먼저 한다. 만들지 않은 계정은 지울 일도 없다(★D7).
            rejectIfUnderAge(socialUser.birthDate());
            member = join(provider, socialUser, request.agreements(), now);
        } else {
            // 이미 커밋된 계정이라 파기에 별도 트랜잭션이 필요하다. 상태를 바꾸기 전에 판정해야
            // 같은 행을 두 트랜잭션이 동시에 건드리지 않는다.
            purgeAndRejectIfUnderAge(member, socialUser.birthDate());
            if (member.getStatus() == MemberStatus.WITHDRAW_PENDING) {
                // 삭제 예정 시각이 지났으면 B4 배치 실행 전이라도 삭제된 계정으로 본다.
                // MemberService.cancelWithdrawal과 같은 규칙 — 여기만 느슨하면 로그인으로 계정이 부활한다.
                if (!member.getDeleteScheduledAt().isAfter(now)) {
                    throw new BusinessException(ErrorCode.UNAUTHORIZED);
                }
                member.cancelWithdrawal();
                loginResult = LOGIN_RESTORED;
            }
        }

        markVerifiedIfProvided(member, socialUser.birthDate());

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

    private Member join(SocialProvider provider, SocialUser socialUser,
                        List<AgreementItem> agreements, LocalDateTime now) {
        Set<AgreementType> agreed = agreedTypes(agreements);
        if (!agreed.containsAll(MANDATORY_AGREEMENTS)) {
            throw new BusinessException(ErrorCode.AGREEMENT_REQUIRED);
        }
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
        for (AgreementType type : agreed) {
            memberAgreementRepository.save(MemberAgreement.agree(member.getId(), type, now));
        }
        return member;
    }

    /**
     * 동의한 종류만 모은다. 미동의는 행을 만들지 않으므로 false는 그대로 버린다 — 행의 유무가
     * 곧 동의 여부다(D20). 같은 종류가 두 번 실려 와도 집합이라 uk_ma에 걸리지 않는다.
     */
    private Set<AgreementType> agreedTypes(List<AgreementItem> agreements) {
        Set<AgreementType> agreed = EnumSet.noneOf(AgreementType.class);
        for (AgreementItem item : agreements) {
            if (Boolean.TRUE.equals(item.agreed())) {
                agreed.add(item.type());
            }
        }
        return agreed;
    }

    /** 가입 전 판정. 아직 아무것도 쓰지 않았으므로 던지기만 하면 계정이 남지 않는다. */
    private void rejectIfUnderAge(LocalDate birthDate) {
        if (birthDate != null && isUnderAge(birthDate)) {
            throw new BusinessException(ErrorCode.UNDER_AGE_SIGNUP_BLOCKED);
        }
    }

    /**
     * 이미 있는 계정의 판정. 소셜이 이번에 처음 생년월일을 준 경우라 계정을 파기해야 한다.
     * VERIFIED는 뒤집지 않는다(AU-3과 같은 규칙).
     */
    private void purgeAndRejectIfUnderAge(Member member, LocalDate birthDate) {
        if (birthDate == null
                || member.getAgeVerification() != AgeVerification.REQUIRED
                || !isUnderAge(birthDate)) {
            return;
        }
        memberAccountPurger.purge(member.getId());
        throw new BusinessException(ErrorCode.UNDER_AGE_SIGNUP_BLOCKED);
    }

    private boolean isUnderAge(LocalDate birthDate) {
        return Period.between(birthDate, LocalDate.now(clock)).getYears() < MINIMUM_AGE;
    }

    /**
     * 소셜 계정이 생년월일을 줬으면 그 자리에서 확정한다. 못 받았으면 REQUIRED로 남아
     * AU-3에서 입력받는다. 미만 판정은 이 지점에 도달하기 전에 이미 걸러졌다.
     */
    private void markVerifiedIfProvided(Member member, LocalDate birthDate) {
        // VERIFIED 확정 후에는 재검증으로 뒤집을 수 없다(AU-3과 같은 규칙)
        if (birthDate == null || member.getAgeVerification() != AgeVerification.REQUIRED) {
            return;
        }
        member.verifyAge(birthDate, AgeVerification.VERIFIED);
    }
}
