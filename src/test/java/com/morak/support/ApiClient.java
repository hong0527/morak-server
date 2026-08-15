package com.morak.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 여정 테스트가 클라이언트처럼 API를 부르는 통로.
 *
 * <p>기능 하나를 보는 테스트는 서비스를 직접 부르는 편이 낫지만(docs/testing.md §2-2),
 * <b>여정은 HTTP로 이어야 의미가 있다.</b> 사슬의 마디마다 인터셉터 게이트가 걸려 있어서 —
 * 생년월일을 넣기 전에는 매칭이 막히고, 퇴출 뒤에도 이의는 열려 있어야 한다 — 서비스를
 * 직접 부르면 그 순서가 지켜지는지를 확인할 수 없다. 게이트를 건너뛴 여정은 "이 순서로도
 * 되더라"를 증명하지 못한다.
 *
 * <p><b>성공을 기대한 호출이 실패하면 그 자리에서 경로·상태·본문을 담아 던진다.</b> 여정은
 * 20단계가 넘어서, 어느 단계가 끊겼는지가 실패 메시지에 없으면 스택을 거슬러 올라가야 한다.
 */
public class ApiClient {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final ObjectMapper responseReader;

    public ApiClient(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.responseReader = responseReader(objectMapper);
    }

    /**
     * 응답을 읽는 쪽 매퍼. 서버는 {@code LocalDateTime}을 명세 §0-1대로 오프셋을 붙여
     * 내보내지만({@code JacksonConfig}) 되읽는 설정은 없다 — 서버가 자기 응답을 파싱할 일이
     * 없기 때문이다. 그 일을 하는 것은 프론트라서, <b>여정 테스트도 프론트와 같은 자리에
     * 선다</b>. 여기서 오프셋 표기를 읽어 내는 것이 곧 "명세대로 나갔는가"의 확인이기도 하다.
     */
    private static ObjectMapper responseReader(ObjectMapper base) {
        return base.rebuild()
                .addModule(new SimpleModule("morakResponseRead")
                        .addDeserializer(LocalDateTime.class,
                                new ValueDeserializer<LocalDateTime>() {
                                    @Override
                                    public LocalDateTime deserialize(JsonParser parser,
                                                                     DeserializationContext ctxt) {
                                        return OffsetDateTime.parse(parser.getString())
                                                .toLocalDateTime();
                                    }
                                }))
                .build();
    }

    // ── 성공을 기대하는 호출 ──

    public <T> T get(String path, String token, Class<T> type) {
        return body(exchange(MockMvcRequestBuilders.get(path), token, null), type, "GET " + path);
    }

    public JsonNode getJson(String path, String token) {
        return json(exchange(MockMvcRequestBuilders.get(path), token, null), "GET " + path);
    }

    public <T> T post(String path, String token, Object requestBody, Class<T> type) {
        return body(exchange(MockMvcRequestBuilders.post(path), token, requestBody), type,
                "POST " + path);
    }

    /** 본문 없는 성공 응답(204·202 등). 상태 확인만 하고 버린다. */
    public void post(String path, String token, Object requestBody) {
        exchangeOk(MockMvcRequestBuilders.post(path), token, requestBody, "POST " + path);
    }

    public <T> T put(String path, String token, Object requestBody, Class<T> type) {
        return body(exchange(MockMvcRequestBuilders.put(path), token, requestBody), type,
                "PUT " + path);
    }

    public <T> T patch(String path, String token, Object requestBody, Class<T> type) {
        return body(exchange(MockMvcRequestBuilders.patch(path), token, requestBody), type,
                "PATCH " + path);
    }

    public void delete(String path, String token) {
        exchangeOk(MockMvcRequestBuilders.delete(path), token, null, "DELETE " + path);
    }

    // ── 실패를 기대하는 호출 ──

    /**
     * 거절될 것을 알고 부른다. 응답의 에러 코드를 돌려주므로 호출부가 그 값을 단언한다.
     * 2xx가 나오면 <b>막혀야 할 것이 열린 것</b>이라 그 자리에서 던진다.
     */
    public String errorCodeOf(String method, String path, String token, Object requestBody) {
        MockHttpServletResponse response =
                exchange(builder(method, path), token, requestBody);
        if (response.getStatus() < 400) {
            throw new AssertionError(
                    "막혀야 할 것이 열려 있다 — %s %s : status=%d, body=%s"
                            .formatted(method, path, response.getStatus(), text(response)));
        }
        return json(response, method + " " + path).path("error").path("code").asText();
    }

    public int statusOf(String method, String path, String token, Object requestBody) {
        return exchange(builder(method, path), token, requestBody).getStatus();
    }

    // ── 내부 ──

    private MockHttpServletRequestBuilder builder(String method, String path) {
        return switch (method) {
            case "GET" -> MockMvcRequestBuilders.get(path);
            case "POST" -> MockMvcRequestBuilders.post(path);
            case "PUT" -> MockMvcRequestBuilders.put(path);
            case "PATCH" -> MockMvcRequestBuilders.patch(path);
            case "DELETE" -> MockMvcRequestBuilders.delete(path);
            default -> throw new IllegalArgumentException("모르는 메서드: " + method);
        };
    }

    private MockHttpServletResponse exchange(MockHttpServletRequestBuilder request, String token,
                                             Object requestBody) {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        // 본문이 없어도 Content-Type을 붙인다. 없으면 415가 나가 실패 원인이 한 겹 가려진다
        request.contentType(MediaType.APPLICATION_JSON);
        if (requestBody != null) {
            request.content(write(requestBody));
        }
        try {
            return mockMvc.perform(request).andReturn().getResponse();
        } catch (Exception e) {
            throw new IllegalStateException("요청을 보내지 못했다", e);
        }
    }

    private void exchangeOk(MockHttpServletRequestBuilder request, String token,
                            Object requestBody, String where) {
        requireSuccess(exchange(request, token, requestBody), where);
    }

    private <T> T body(MockHttpServletResponse response, Class<T> type, String where) {
        requireSuccess(response, where);
        try {
            return responseReader.readValue(text(response), type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "%s 응답을 %s로 읽지 못했다: %s".formatted(where, type.getSimpleName(),
                            text(response)), e);
        }
    }

    private JsonNode json(MockHttpServletResponse response, String where) {
        try {
            return responseReader.readTree(text(response));
        } catch (Exception e) {
            throw new IllegalStateException("%s 응답이 JSON이 아니다: %s"
                    .formatted(where, text(response)), e);
        }
    }

    /** 실패 메시지에 여정의 어느 마디가 끊겼는지를 담는다. */
    private void requireSuccess(MockHttpServletResponse response, String where) {
        if (response.getStatus() >= 400) {
            throw new AssertionError("여정이 여기서 끊겼다 — %s : status=%d, body=%s"
                    .formatted(where, response.getStatus(), text(response)));
        }
    }

    private String text(MockHttpServletResponse response) {
        try {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            return response.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<본문을 읽지 못했다>";
        }
    }

    private String write(Object value) {
        if (value instanceof String raw) {
            return raw;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("요청 본문을 만들지 못했다", e);
        }
    }
}
