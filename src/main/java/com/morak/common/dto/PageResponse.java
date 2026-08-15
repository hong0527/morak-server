package com.morak.common.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 목록 응답의 공통 포맷 (API명세서 §0-1). Spring의 {@code Page}를 그대로 직렬화하지 않는다 —
 * {@code pageable}·{@code sort}·{@code first}·{@code numberOfElements}까지 딸려 나가고,
 * 그 구조는 Spring Data 버전에 묶여 있어 명세의 응답 예시와 어긋난다.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
