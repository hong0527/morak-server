package com.morak.match.repository;

import com.morak.match.entity.MatchLock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchLockRepository extends JpaRepository<MatchLock, String> {
}
