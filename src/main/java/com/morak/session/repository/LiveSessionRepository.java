package com.morak.session.repository;

import com.morak.session.entity.LiveSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 2단계는 생성만 썼다. 조회(SS-1)·웹훅 역참조(SS-10)가 3단계에서 붙었고, 종료 배치(B1)는
 * 5단계에서 붙는다.
 */
public interface LiveSessionRepository extends JpaRepository<LiveSession, Long> {

    /** SS-10이 방 이름으로 세션을 되짚는 경로. uk_ls_room이 유일성을 보장한다. */
    Optional<LiveSession> findByLivekitRoomName(String livekitRoomName);
}
