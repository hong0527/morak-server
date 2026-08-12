package com.morak.common.dto;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 페이지 파라미터 (API명세서 §0-1). 기본 20, 최대 50이고 초과는 400
 * {@code VALIDATION_FAILED}다.
 *
 * <p>상한을 컨트롤러마다 두지 않는 이유는 목록 API가 앞으로 여러 개 생기기 때문이다.
 * 한 곳에서 막지 않으면 {@code size=100000} 하나로 전체 테이블을 뽑아가는 경로가
 * 어딘가에 반드시 남는다.
 */
public record PageParams(int page, int size) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    public static PageParams of(Integer page, Integer size) {
        int resolvedPage = page == null ? DEFAULT_PAGE : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("page", "0 이상이어야 합니다."));
        }
        if (resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("size", "1 이상 %d 이하여야 합니다.".formatted(MAX_SIZE)));
        }
        return new PageParams(resolvedPage, resolvedSize);
    }

    public Pageable toPageable(Sort sort) {
        return PageRequest.of(page, size, sort);
    }
}
