package com.morak.member.service;

import com.morak.common.batch.BatchGuard;
import com.morak.dev.DevBatch;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.member.type.MemberStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * B4 탈퇴 계정 파기 배치. 대상을 고르는 일만 하고 처리는 {@link MemberPurgeService}에 맡긴다 —
 * 같은 빈 안에서 부르면 프록시를 타지 않아 회원별 트랜잭션 경계가 서지 않는다.
 *
 * <p>회원 단위로 트랜잭션을 나누는 이유는 파기가 되돌릴 수 없는 작업이기 때문이다. 한 트랜잭션에
 * 전원을 담으면 마지막 한 명에서 난 오류가 이미 끝난 파기까지 되돌리고, 다음 실행은 같은 지점에서
 * 다시 실패한다.
 *
 * <p>재실행해도 결과가 같다. 대상 조회가 {@code WITHDRAW_PENDING}만 보므로 이미 파기된 회원은
 * 두 번째 실행에서 집히지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MemberPurgeBatch implements DevBatch {

    private static final Logger log = LoggerFactory.getLogger(MemberPurgeBatch.class);

    private final MemberPurgeService memberPurgeService;
    private final MemberRepository memberRepository;
    private final Clock clock;

    @Override
    public String name() {
        return "B4";
    }

    /**
     * 매일 새벽 4시. 유예가 일 단위라 이보다 촘촘하게 돌 이유가 없고, 파기는 다른 배치와
     * 겹치면 곤란한 작업이라 트래픽이 가장 적은 시간을 고른다.
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void schedule() {
        run();
    }

    @Override
    public int run() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Member> targets = memberRepository.findByStatusAndDeleteScheduledAtBefore(
                MemberStatus.WITHDRAW_PENDING, now);
        int purged = 0;
        for (Member target : targets) {
            purged += BatchGuard.guarded(log, "탈퇴 파기", target.getId(),
                    () -> memberPurgeService.purgeWithdrawn(target.getId()));
        }
        if (purged > 0) {
            log.info("탈퇴 계정 파기 {}건", purged);
        }
        return purged;
    }
}
