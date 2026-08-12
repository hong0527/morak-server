package com.morak.member.service;

import com.morak.match.entity.MatchLock;
import com.morak.match.repository.MatchLockRepository;
import com.morak.member.repository.MediaConsentRepository;
import com.morak.member.repository.MemberAgreementRepository;
import com.morak.member.repository.MemberRepository;
import com.morak.member.repository.StreakDayRepository;
import com.morak.point.repository.PointLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만 14세 미만 판정 계정의 파기(★D7). 탈퇴(AU-4)의 30일 유예를 적용하지 않는다 —
 * 가입이 성립하지 않은 상태라 보관할 근거가 없다.
 *
 * <p>별도 빈인 이유는 전파 속성 때문이다. 판정한 쪽은 403을 던져야 하는데, 같은 트랜잭션에서
 * 지우고 던지면 그 예외의 롤백이 삭제까지 되돌려 계정이 그대로 남는다. {@code REQUIRES_NEW}로
 * 파기만 먼저 커밋시키고, 부르는 쪽은 그 뒤에 예외를 던진다. 자기 호출은 프록시를 타지 않아
 * 같은 클래스 안의 메서드로는 이 경계를 만들 수 없다.
 *
 * <p>이미 커밋된 계정에만 쓴다. 가입 트랜잭션 안에서 미만이 판정되면 아직 커밋 전이라
 * 그냥 던져서 롤백시키는 편이 맞고, 그때는 이 빈을 부르면 안 된다(다른 트랜잭션의 미커밋 행은
 * 보이지 않는다).
 */
@Component
@RequiredArgsConstructor
public class MemberAccountPurger {

    private final MemberRepository memberRepository;
    private final MemberAgreementRepository memberAgreementRepository;
    private final MediaConsentRepository mediaConsentRepository;
    private final StreakDayRepository streakDayRepository;
    private final MatchLockRepository matchLockRepository;
    private final PointLedgerRepository pointLedgerRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purge(Long memberId) {
        // member를 참조하는 행부터 지운다. FK가 걸려 있어 순서를 바꾸면 제약 위반이다.
        memberAgreementRepository.deleteByMemberId(memberId);
        streakDayRepository.deleteByMemberId(memberId);
        // 회원당 1행이라 PK로 지운다. 없으면 아무 일도 일어나지 않는다.
        mediaConsentRepository.deleteById(memberId);
        // 가입 트랜잭션이 함께 만든 잠금 행. 남으면 회원 없는 고아 행이 쌓인다.
        matchLockRepository.deleteById(MatchLock.memberKey(memberId));
        // 웰컴 포인트 원장(api-spec AU-3 부수효과). 원장을 지우는 것은 여기뿐이고, 정정을
        // 역분개로 하는 원칙의 예외인 이유는 가입이 성립하지 않아 그 행이 거래 기록이 아니기
        // 때문이다. B4 탈퇴 파기가 커머스 원장을 남기는 것과 기준이 다르다 — 이 계정은
        // 세션도 주문도 겪을 수 없으므로 지울 대상이 웰컴 1행뿐이다.
        pointLedgerRepository.deleteByMemberId(memberId);
        memberRepository.deleteById(memberId);
    }
}
