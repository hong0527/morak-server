package com.morak.session.repository;

import com.morak.session.entity.Warning;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 경고는 서버만 만든다. 회원용 조회 API는 없고 세션 결과(SS-8)는 참가자 행의
 * {@code warning_count}를 쓰므로, 4단계에서 필요한 것은 INSERT뿐이었다.
 * 읽는 경로는 9단계의 AD-2 하나다 — 관리자가 신고 대상자의 과거 행동을 볼 때만 쓴다.
 */
public interface WarningRepository extends JpaRepository<Warning, Long> {

    /**
     * AD-2 경고 로그. 최근 것부터 제한된 개수만 읽는다 — 판단에 필요한 것은 최근 경향이고,
     * 오래 쓴 계정의 전량을 상세 응답에 담을 이유가 없다.
     */
    List<Warning> findTop20ByMemberIdOrderByIdDesc(Long memberId);
}
