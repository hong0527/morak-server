package com.morak.member.repository;

import com.morak.member.entity.MemberAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberAgreementRepository extends JpaRepository<MemberAgreement, Long> {

    /** 만 14세 미만 파기(AU-3)와 탈퇴 파기(B4)가 회원의 동의 이력을 함께 지운다. */
    void deleteByMemberId(Long memberId);
}
