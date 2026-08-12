package com.morak.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로그인한 회원 번호를 컨트롤러 파라미터로 받는다.
 *
 * <pre>
 * public MemberResponse me(@LoginMember Long memberId) { ... }
 * </pre>
 *
 * <p>토큰 검증은 인터셉터가 이미 끝냈으므로 여기서는 값만 꺼내 쓴다.
 * 컨트롤러마다 헤더를 파싱하면 검증을 빠뜨리는 곳이 생긴다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginMember {
}
