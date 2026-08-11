package com.morak.report.type;

/**
 * 완주 판정을 누가 내렸는지.
 *
 * <p>대부분은 배치가 자동으로 판정하지만({@code AI}), 기준 경계에 걸린 건은 관리자 검토 큐로
 * 넘어가 사람이 결정한다({@code ADMIN}). 나중에 판정이 뒤집혔을 때 자동 판정이 틀렸던 것인지
 * 사람이 뒤집은 것인지 구분할 수 있어야 해서 남긴다.
 */
public enum DecidedBy {
    AI,
    ADMIN
}
