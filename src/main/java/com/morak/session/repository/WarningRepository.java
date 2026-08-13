package com.morak.session.repository;

import com.morak.session.entity.Warning;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 경고는 서버만 만든다. 회원용 조회 API는 없고 세션 결과(SS-8)는 참가자 행의
 * {@code warning_count}를 쓰므로, 4단계에서 필요한 것은 INSERT뿐이었다.
 * 읽는 경로는 관리자 둘뿐이다 — 신고 대상자의 과거 행동(AD-2)과 퇴출 이의의 근거(AD-9).
 */
public interface WarningRepository extends JpaRepository<Warning, Long> {

    /**
     * AD-2 경고 로그. 최근 것부터 제한된 개수만 읽는다 — 판단에 필요한 것은 최근 경향이고,
     * 오래 쓴 계정의 전량을 상세 응답에 담을 이유가 없다.
     */
    List<Warning> findTop20ByMemberIdOrderByIdDesc(Long memberId);

    /**
     * AD-9 이의 심사가 읽는 퇴출 근거 경고 3건. 상한 없이 전량인 것은 세션 스코프 경고가
     * 임계(3)에서 퇴출로 끝나 그 이상 쌓일 수 없기 때문이다.
     */
    List<Warning> findBySessionIdAndMemberIdOrderBySeqAsc(Long sessionId, Long memberId);

    /**
     * 탈퇴 파기(B4)가 회원의 경고 이력을 지운다. 경고는 세션 스코프라(D11) 계정을 떠나면
     * 남을 근거가 없다 — 퇴출과 그 이의({@code eviction}·{@code appeal_case})는 분쟁 대응
     * 근거라 남기는 것과 기준이 갈린다.
     */
    void deleteByMemberId(Long memberId);
}
