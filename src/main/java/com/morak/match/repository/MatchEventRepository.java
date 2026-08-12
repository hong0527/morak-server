package com.morak.match.repository;

import com.morak.match.entity.MatchEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** 추가 전용 지표 로그. 수정·삭제 경로를 만들지 않는다. */
public interface MatchEventRepository extends JpaRepository<MatchEvent, Long> {
}
