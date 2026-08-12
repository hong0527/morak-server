package com.morak.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 게시글 좋아요. 토글이라 취소는 행 삭제로 처리한다 — 상태 컬럼을 두면 좋아요 수를
 * 셀 때마다 취소분을 걸러야 하고, 동시 토글에서 uk_post_like의 중복 방어가 무력해진다.
 */
@Entity
@Table(
        name = "post_like",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_post_like",
                columnNames = {"post_id", "member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "liked_at", nullable = false)
    private LocalDateTime likedAt;

    private PostLike(Long postId, Long memberId, LocalDateTime likedAt) {
        this.postId = postId;
        this.memberId = memberId;
        this.likedAt = likedAt;
    }

    public static PostLike like(Long postId, Long memberId, LocalDateTime likedAt) {
        return new PostLike(postId, memberId, likedAt);
    }
}
