package com.morak.member.repository;

import com.morak.member.entity.BlockedSocialHash;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockedSocialHashRepository extends JpaRepository<BlockedSocialHash, String> {
}
