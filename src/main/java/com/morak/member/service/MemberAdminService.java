package com.morak.member.service;

import com.morak.common.dto.PageParams;
import com.morak.common.dto.PageResponse;
import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import com.morak.member.dto.response.WithdrawalSummaryResponse;
import com.morak.member.entity.Member;
import com.morak.member.repository.MemberRepository;
import com.morak.member.type.MemberStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AD-8 탈퇴 처리 결과 (API명세서 AD-8, NFR-202).
 *
 * <p>파기 자체는 B4({@code MemberPurgeBatch})가 하고 이 화면은 그 결과를 확인만 한다 —
 * <b>파기가 예정대로 일어났는지 확인할 창구가 없으면 B4가 조용히 멈춰도 아무도 모른다.</b>
 * 개인정보 파기는 늦었다는 사실이 드러나야 하는 종류의 일이라 조회를 따로 둔다.
 *
 * <p>관리자 여부는 여기서 보지 않는다 — {@code /api/admin/**} 전체를 전역 인터셉터의
 * ③ 역할 검사가 막는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MemberAdminService {

    /** 탈퇴 신청과 무관한 상태는 이 목록의 대상이 아니다. */
    private static final Set<MemberStatus> WITHDRAWAL_STATUSES =
            EnumSet.of(MemberStatus.WITHDRAW_PENDING, MemberStatus.DELETED);

    /** 신청이 늦은 순. 파기가 임박한 건과 이미 지난 건이 위로 오는 정렬은 조회 조건이 아니라 화면의 몫이다. */
    private static final Sort WITHDRAWAL_SORT =
            Sort.by(Sort.Order.desc("withdrawRequestedAt"), Sort.Order.desc("id"));

    private final MemberRepository memberRepository;

    public PageResponse<WithdrawalSummaryResponse> getWithdrawals(MemberStatus status,
                                                                   Integer page, Integer size) {
        if (status != null && !WITHDRAWAL_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    Map.of("status", "조회 가능한 상태는 " + WITHDRAWAL_STATUSES + "입니다."));
        }
        PageParams params = PageParams.of(page, size);
        Page<Member> members = memberRepository.findByStatusIn(
                status == null ? WITHDRAWAL_STATUSES : EnumSet.of(status),
                params.toPageable(WITHDRAWAL_SORT));
        return PageResponse.of(members, WithdrawalSummaryResponse::from);
    }
}
