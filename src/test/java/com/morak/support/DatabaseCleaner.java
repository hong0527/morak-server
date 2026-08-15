package com.morak.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트 사이의 데이터 정리.
 *
 * <p><b>롤백으로 정리하지 않는다.</b> 여기서 확인하려는 것의 절반이 동시 실행과 배치이고, 그
 * 둘은 커밋된 데이터를 다른 스레드·다른 트랜잭션에서 보는 일이다. 테스트를
 * {@code @Transactional}로 감싸면 그 관찰 자체가 성립하지 않고, "잔액 캐시가 정말 DB에 남았나"
 * 같은 판정은 롤백 대상 컨텍스트 안에서 늘 통과해 버린다.
 *
 * <p>{@code match_lock}만 남긴다. 조건 행 4개는 기동 시 시더가 한 번 만들고 그 뒤로 만드는
 * 경로가 없어서, 지우면 다음 테스트의 매칭이 "시더가 돌지 않았다"로 죽는다. 회원 잠금 행은
 * 회원과 함께 사라져야 하므로 키 접두사로 골라 지운다.
 *
 * <p>식별자는 이어서 증가시킨다({@code TRUNCATE}의 기본값). 되돌리면 테스트마다 같은 id가
 * 다시 나오고, 메모리에 남는 재접속 유예 창({@code ReconnectGraceRegistry})이 이전 테스트의
 * 세션·회원 쌍으로 새 테스트의 참가자를 집어 든다.
 */
public class DatabaseCleaner {

    private static final String LOCK_TABLE = "MATCH_LOCK";

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clean() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : tables()) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        jdbcTemplate.update("DELETE FROM match_lock WHERE lock_key LIKE 'member:%'");
    }

    private List<String> tables() {
        return jdbcTemplate.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'PUBLIC'
                   AND table_type = 'BASE TABLE'
                   AND table_name <> ?
                """, String.class, LOCK_TABLE);
    }
}
