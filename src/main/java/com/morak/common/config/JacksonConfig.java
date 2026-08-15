package com.morak.common.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

@Configuration
public class JacksonConfig {

    /**
     * 명세 §0-1은 시각을 ISO-8601(+09:00)로 표기한다. DTO는 LocalDateTime이라 기본
     * 직렬화에 오프셋이 없으므로, 직렬화 시점에 서버 타임존(Clock의 zone) 오프셋을 붙인다.
     * DTO를 OffsetDateTime으로 바꾸는 대신 여기서 한 번에 처리해 도메인 코드를 건드리지 않는다.
     *
     * <p><b>초 미만은 버린다.</b> 자르지 않으면 DB가 준 나노초가 그대로 나가는데, 자릿수가
     * 값마다 달라(소수점 5~6자리 가변) 클라이언트가 고정 포맷으로 받을 수 없다. 표준
     * 파서를 쓰면 무해하지만 {@code yyyy-MM-dd'T'HH:mm:ssXXX}로 파싱하거나 문자열을
     * 비교하는 구현은 전부 깨진다. 명세가 초 단위로 예시를 적고 있으므로 응답을 명세에
     * 맞춘다 — 저장값은 그대로이고 잘리는 것은 표현뿐이다.
     */
    @Bean
    public JsonMapperBuilderCustomizer localDateTimeOffsetCustomizer(Clock clock) {
        return builder -> builder.addModule(
                new SimpleModule("morakLocalDateTimeOffset")
                        .addSerializer(LocalDateTime.class, new ValueSerializer<LocalDateTime>() {
                            @Override
                            public void serialize(LocalDateTime value, JsonGenerator gen,
                                                  SerializationContext ctxt) {
                                gen.writeString(value.truncatedTo(ChronoUnit.SECONDS)
                                        .atZone(clock.getZone())
                                        .toOffsetDateTime()
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                            }
                        }));
    }
}
