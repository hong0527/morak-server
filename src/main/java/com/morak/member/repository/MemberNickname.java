package com.morak.member.repository;

/**
 * 회원 행에서 닉네임만 꺼내는 조회 전용 뷰.
 *
 * <p>닉네임 하나가 필요할 때 {@code findAllById}로 엔티티를 통째로 읽으면 소셜 식별자·생년월일·
 * 포인트 잔액까지 영속성 컨텍스트에 올라온다. 그 값들은 응답에 실리지 않지만, 메모리에 있는 한
 * 힙 덤프와 디버깅 로그에 딸려 나오고 누군가 그 자리에서 꺼내 쓰는 코드를 나중에 쓸 수 있다.
 * 애초에 읽지 않는 편이 확실하다.
 *
 * <p>닫힌 프로젝션이라 Hibernate가 {@code SELECT id, nickname}만 낸다 — 엔티티가 아니므로
 * 더티 체킹 대상도 아니다.
 */
public interface MemberNickname {

    Long getId();

    String getNickname();
}
