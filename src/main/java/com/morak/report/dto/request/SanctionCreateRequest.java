package com.morak.report.dto.request;

import com.morak.report.type.SanctionType;

/**
 * AD-4 제재 단독 적용. 본문은 평평하지만 의미는 AD-3의 {@code sanction}과 같아서
 * {@link SanctionCommand}로 옮겨 담아 같은 검증·같은 서비스 메서드를 탄다.
 *
 * <p>{@code caseId}는 근거 케이스이고 생략할 수 있다 — 신고 없이 내리는 제재 경로가
 * 이 API의 존재 이유다.
 */
public record SanctionCreateRequest(SanctionType type, Integer days, Long caseId) {

    public SanctionCommand toCommand() {
        return new SanctionCommand(type, days);
    }
}
