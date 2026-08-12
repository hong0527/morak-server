package com.morak.session.type;

/**
 * 퇴출 이의({@code appeal_case.decided_by})를 누가 판단했는지.
 *
 * <p>v1의 이의 처리는 관리자(AD-6)뿐이라 실제로 기록되는 값은 {@code ADMIN} 하나다.
 * {@code AI}를 남겨 두는 것은 자리비움 판정 자체가 온디바이스 AI의 보고에서 출발하기 때문이다 —
 * 나중에 자동 인용 경로가 생겼을 때, 뒤집힌 판정이 자동 판단이었는지 사람의 판단이었는지
 * 구분할 수 없으면 그 경로가 옳게 동작했는지 사후에 확인할 방법이 없다.
 */
public enum DecidedBy {
    AI,
    ADMIN
}
