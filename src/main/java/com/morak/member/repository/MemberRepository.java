package com.morak.member.repository;

import com.morak.member.entity.Member;
import com.morak.member.type.MemberStatus;
import com.morak.member.type.SocialProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    /** 탈퇴 처리 배치가 삭제 예정 시각이 지난 회원을 찾는다. */
    List<Member> findByStatusAndDeleteScheduledAtBefore(MemberStatus status, LocalDateTime now);
}
