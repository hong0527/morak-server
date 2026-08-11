package com.morak.reaction.entity;

import com.morak.reaction.type.StickerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 인증에 붙이는 응원 스티커.
 *
 * <p>토글 방식이라 취소는 행 삭제다. 그래서 상태를 바꾸는 메서드가 없다.
 * uk_sr는 같은 사람이 같은 인증에 같은 스티커를 두 번 붙이는 동시 요청을 DB에서 막는다.
 */
@Entity
@Table(
        name = "sticker_reaction",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sr",
                columnNames = {"proof_id", "member_id", "sticker_type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StickerReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proof_id", nullable = false)
    private Long proofId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sticker_type", nullable = false, length = 20)
    private StickerType stickerType;

    @Column(name = "reacted_at", nullable = false)
    private LocalDateTime reactedAt;

    private StickerReaction(Long proofId, Long memberId, StickerType stickerType,
                            LocalDateTime reactedAt) {
        this.proofId = proofId;
        this.memberId = memberId;
        this.stickerType = stickerType;
        this.reactedAt = reactedAt;
    }

    public static StickerReaction react(Long proofId, Long memberId, StickerType stickerType,
                                        LocalDateTime reactedAt) {
        return new StickerReaction(proofId, memberId, stickerType, reactedAt);
    }
}
