package com.morak.match.repository;

import com.morak.match.entity.MatchLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MatchLockRepository extends JpaRepository<MatchLock, String> {

    /**
     * 잠금 행을 FOR UPDATE로 잡는다. 이 행을 잡는 순서는 "회원 행 → 조건 행"으로 고정한다 —
     * 역순으로 잡는 경로가 하나라도 생기면 교차 대기 데드락이다.
     *
     * <p>없는 행을 잠그려 하면 빈 Optional이 온다. 이때 행을 만들어 이어가면 안 된다(MatchLock 주석).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MatchLock> findByLockKey(String lockKey);
}
