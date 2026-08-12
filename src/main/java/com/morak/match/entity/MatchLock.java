package com.morak.match.entity;

import com.morak.common.type.GoalCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매칭 직렬화를 위한 잠금 전용 테이블. 행 자체가 잠금 대상이라 컬럼은 키 하나뿐이다.
 *
 * <p>행을 매칭 도중에 만들지 않는다. 조건 행은 기동 시 ApplicationRunner가 전부 시드하고,
 * 회원 행은 가입 트랜잭션에서 동반 INSERT한다. 존재하지 않는 행을 FOR UPDATE로 잠그면
 * 갭 락이 없어 동시 진입한 두 트랜잭션이 모두 0행을 보고 INSERT를 시도하며, 그 경합은
 * 같은 트랜잭션 안에서 복구할 수 없다(H2 실측).
 *
 * <p>키 문자열 조립은 이 클래스에만 둔다. 형식이 한 글자라도 어긋나면 서로 다른 행을
 * 잠그게 되어 잠금이 무력화된다.
 */
@Entity
@Table(name = "match_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchLock {

    @Id
    @Column(name = "lock_key", length = 80)
    private String lockKey;

    private MatchLock(String lockKey) {
        this.lockKey = lockKey;
    }

    public static MatchLock forMember(Long memberId) {
        return new MatchLock(memberKey(memberId));
    }

    public static MatchLock forCondition(GoalCategory category, int dailyTargetMinutes, int periodDays) {
        return new MatchLock(conditionKey(category, dailyTargetMinutes, periodDays));
    }

    public static String memberKey(Long memberId) {
        return "member:" + memberId;
    }

    public static String conditionKey(GoalCategory category, int dailyTargetMinutes, int periodDays) {
        return "match:" + category.name() + ":" + dailyTargetMinutes + ":" + periodDays;
    }
}
