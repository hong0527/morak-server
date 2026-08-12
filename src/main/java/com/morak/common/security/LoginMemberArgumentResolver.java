package com.morak.common.security;

import com.morak.common.error.BusinessException;
import com.morak.common.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@code @LoginMember Long memberId} 파라미터에 인터셉터가 확인한 회원 번호를 넣는다.
 *
 * <p>토큰을 다시 파싱하지 않고 인터셉터가 request attribute에 담아둔 값을 꺼낸다.
 * 검증 로직이 두 곳이면 언젠가 서로 어긋난다.
 */
@Component
public class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginMember.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object memberId = webRequest.getAttribute(AuthInterceptor.MEMBER_ID_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (memberId == null) {
            // 인터셉터 제외 경로의 핸들러가 @LoginMember를 쓰면 값이 없다. null을 조용히 넘기면
            // NPE가 서비스 깊은 곳에서 터지므로 여기서 바로 끊는다.
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return memberId;
    }
}
