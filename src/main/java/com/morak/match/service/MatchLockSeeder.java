package com.morak.match.service;

import com.morak.match.entity.MatchLock;
import com.morak.match.repository.MatchLockRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조건 잠금 행 시드. 대기열을 건드리는 트랜잭션은 전부 {@code match:{minutes}} 행을
 * {@code FOR UPDATE}로 잡는데, 그 행이 없으면 잠글 대상이 없다.
 *
 * <p>없는 행을 매칭 도중에 만들어 이어가는 방식은 쓸 수 없다. H2에는 갭 락이 없어 동시
 * 진입한 두 트랜잭션이 모두 "행 없음"을 보고 INSERT를 시도하고, 그 경합은 같은 트랜잭션
 * 안에서 복구할 수 없다. 그래서 기동 시점에 미리 만들어 둔다.
 *
 * <p>재기동마다 실행되므로 이미 있는 행은 건너뛴다. 시드는 여러 번 돌아도 결과가 같아야
 * 하고, 그 근거는 코드가 아니라 {@code lock_key} 기본키다.
 */
@Component
public class MatchLockSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MatchLockSeeder.class);

    private final MatchLockRepository matchLockRepository;
    private final List<Integer> targetMinutesOptions;

    public MatchLockSeeder(MatchLockRepository matchLockRepository,
                           @Value("${morak.match.target-minutes-options}")
                           List<Integer> targetMinutesOptions) {
        this.matchLockRepository = matchLockRepository;
        this.targetMinutesOptions = targetMinutesOptions;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;
        for (int targetMinutes : targetMinutesOptions) {
            String lockKey = MatchLock.conditionKey(targetMinutes);
            if (matchLockRepository.existsById(lockKey)) {
                continue;
            }
            matchLockRepository.save(MatchLock.forCondition(targetMinutes));
            created++;
        }
        log.info("매칭 조건 잠금 행 시드 완료 — 조건 {}종, 신규 {}행", targetMinutesOptions.size(), created);
    }
}
