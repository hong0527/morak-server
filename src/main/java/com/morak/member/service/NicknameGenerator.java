package com.morak.member.service;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import jakarta.persistence.EntityManager;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 표시용 익명 닉네임을 만든다. 형태는 {@code 익명 토끼047}.
 *
 * <p>카카오 닉네임은 실명인 경우가 많아 그대로 노출하면 익명 서비스라는 전제가 깨진다.
 * 타인에게 보이는 화면에는 서버가 만든 이 닉네임만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class NicknameGenerator {

    private static final String PREFIX = "익명 ";

    private static final String[] ANIMALS = {
            "토끼", "여우", "수달", "판다", "사슴", "고래", "펭귄", "부엉이",
            "다람쥐", "고슴도치", "돌고래", "알파카", "물개", "너구리", "참새", "거북이",
    };

    /**
     * 조합 공간(동물 16종 x 1000)이 참여 규모보다 훨씬 커서 충돌 확률이 낮다.
     * 이 횟수를 다 쓰고도 실패하면 조합이 고갈에 가깝다는 뜻이라 재시도 대신 실패시킨다.
     */
    private static final int MAX_ATTEMPTS = 20;

    // 중복 검사는 여기서만 필요해서 리포지토리에 메서드를 늘리지 않고 직접 센다
    private final EntityManager entityManager;

    public String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = PREFIX
                    + ANIMALS[random.nextInt(ANIMALS.length)]
                    + String.format("%03d", random.nextInt(1000));
            if (!isTaken(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private boolean isTaken(String nickname) {
        Long count = entityManager
                .createQuery("select count(m) from Member m where m.nickname = :nickname", Long.class)
                .setParameter("nickname", nickname)
                .getSingleResult();
        return count > 0;
    }
}
