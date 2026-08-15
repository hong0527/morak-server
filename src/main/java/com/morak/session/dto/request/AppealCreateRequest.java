package com.morak.session.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AP-1 이의 사유. 관리자가 AD-6에서 판단할 유일한 당사자 진술이라 선택 항목이 아니다 —
 * 사유 없는 이의는 "퇴출이 부당하다"는 주장만 남고 검토할 재료가 없다.
 */
public record AppealCreateRequest(@NotBlank @Size(max = 200) String reasonText) {

    // 필드가 하나뿐인 record는 Jackson이 JSON 전체를 그 값으로 취급하므로 프로퍼티 방식으로 고정한다
    @JsonCreator
    public AppealCreateRequest {}
}
