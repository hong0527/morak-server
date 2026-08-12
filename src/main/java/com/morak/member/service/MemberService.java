package com.morak.member.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.dto.request.BirthDateRequest;
import com.morak.member.dto.response.AgeVerificationResponse;
import com.morak.member.dto.response.MemberMeResponse;
import com.morak.member.dto.response.WithdrawalResponse;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.member.type.AgeVerification;
import com.morak.member.type.MemberStatus;
import com.morak.report.entity.Sanction;
import com.morak.report.repository.SanctionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MemberService {

    /** 만 나이 하한. api-spec §4 AU-3의 "만 14세 미만 차단" 기준. */
    private static final int MINIMUM_AGE = 14;

    private final MemberRepository memberRepository;
    private final SanctionRepository sanctionRepository;
    private final Clock clock;
    private final int withdrawalGraceDays;

    public MemberService(MemberRepository memberRepository,
                         SanctionRepository sanctionRepository,
                         Clock clock,
                         @Value("${morak.withdrawal.grace-days}") int withdrawalGraceDays) {
        this.memberRepository = memberRepository;
        this.sanctionRepository = sanctionRepository;
        this.clock = clock;
        this.withdrawalGraceDays = withdrawalGraceDays;
    }

    public MemberMeResponse getMe(Long memberId) {
        Member member = findMember(memberId);
        LocalDateTime now = LocalDateTime.now(clock);
        List<Sanction> effective = sanctionRepository.findByMemberId(memberId).stream()
                .filter(sanction -> sanction.isEffectiveAt(now))
                .toList();
        // endsAt 계산은 인터셉터 429 응답과 같은 규칙이어야 해서 Sanction.latestEndsAt에 모았다
        MemberMeResponse.Sanction sanction = effective.isEmpty()
                ? null
                : new MemberMeResponse.Sanction(true, Sanction.latestEndsAt(effective));
        // TODO: 4단계(PF-1)에서 MediaConsentRepository 연결 — 행 존재 여부로 mediaConsented를 채운다
        return MemberMeResponse.from(member, false, sanction);
    }

    @Transactional
    public AgeVerificationResponse verifyAge(Long memberId, BirthDateRequest request) {
        Member member = findMember(memberId);
        // UNDER_AGE 판정 후 재입력으로 결과를 뒤집을 수 없어야 하므로 REQUIRED가 아니면 전부 거부한다
        if (member.getAgeVerification() != AgeVerification.REQUIRED) {
            throw new BusinessException(ErrorCode.ALREADY_VERIFIED);
        }
        int age = Period.between(request.birthDate(), LocalDate.now(clock)).getYears();
        AgeVerification result =
                age >= MINIMUM_AGE ? AgeVerification.VERIFIED : AgeVerification.UNDER_AGE;
        member.verifyAge(request.birthDate(), result);
        return AgeVerificationResponse.from(member);
    }

    @Transactional
    public WithdrawalResponse requestWithdrawal(Long memberId) {
        Member member = findMember(memberId);
        if (member.getStatus() == MemberStatus.WITHDRAW_PENDING) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_PENDING);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        member.requestWithdrawal(now, now.plusDays(withdrawalGraceDays));
        // TODO: 2·3단계에서 매칭 취소·그룹 퇴장 연결 — 활성 매칭 요청 CANCELLED, 진행 그룹 LEFT(WITHDRAWAL)
        return WithdrawalResponse.from(member);
    }

    @Transactional
    public void cancelWithdrawal(Long memberId) {
        Member member = findMember(memberId);
        if (member.getStatus() != MemberStatus.WITHDRAW_PENDING) {
            throw new BusinessException(ErrorCode.NOT_WITHDRAWING);
        }
        // 삭제 예정 시각이 지났으면 B4 배치 실행 전이라도 삭제된 계정으로 본다.
        // 명세의 "DELETED → 401 UNAUTHORIZED" 규칙과 맞춘 판단이다.
        if (!member.getDeleteScheduledAt().isAfter(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        member.cancelWithdrawal();
    }

    private Member findMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        // 인터셉터가 막지 못한 경로로 들어와도 삭제된 계정은 어떤 회원 API도 쓸 수 없다
        if (member.getStatus() == MemberStatus.DELETED) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return member;
    }
}
