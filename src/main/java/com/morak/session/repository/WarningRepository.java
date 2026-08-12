package com.morak.session.repository;

import com.morak.session.entity.Warning;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 경고는 서버만 만든다. 조회 API가 따로 없고 세션 결과(SS-8)는 참가자 행의
 * {@code warning_count}를 쓰므로, 4단계에서 필요한 것은 INSERT뿐이다.
 */
public interface WarningRepository extends JpaRepository<Warning, Long> {
}
