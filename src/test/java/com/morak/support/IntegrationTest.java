package com.morak.support;

import com.morak.MorakServerApplication;
import com.morak.dev.AdjustableClock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 통합 테스트의 공통 바탕. dev 프로필(H2·조작 가능한 시계·개발용 소셜 로그인)에서 애플리케이션
 * 전체를 띄운다.
 *
 * <p><b>테스트에 {@code @Transactional}을 걸지 않는다.</b> 확인하려는 것의 상당수가 커밋된 뒤에야
 * 성립한다 — 동시 요청은 남의 트랜잭션이 커밋한 결과를 봐야 하고, 완주 표시가 정말 DB에 닿았는지는
 * 지급이 영속성 컨텍스트를 비운 뒤 다시 읽어야 알 수 있다. 롤백에 기대면 그 판정이 전부
 * 무의미해지므로 정리는 {@link DatabaseCleaner}가 명시적으로 한다.
 *
 * <p>시각은 {@link AdjustableClock}으로만 움직인다. 기다리거나 리플렉션으로 필드를 고치는 방식은
 * 판정 기준이 코드가 아니라 테스트에 생기게 만든다.
 */
@SpringBootTest(classes = {MorakServerApplication.class, TestSupportConfig.class})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    /** 자정·월말 경계에 걸리지 않는 기준 시각. 날짜 판정이 실행일에 따라 달라지지 않게 고정한다. */
    protected static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 3, 10, 9, 0);

    @Autowired
    protected AdjustableClock clock;

    @Autowired
    protected TestFixtures fixtures;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void resetState() {
        databaseCleaner.clean();
        clock.reset();
    }

    @AfterEach
    void restoreClock() {
        clock.reset();
    }

    protected LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
