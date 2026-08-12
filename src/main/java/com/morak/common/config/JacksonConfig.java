package com.morak.common.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
     */
    @Bean
    public JsonMapperBuilderCustomizer localDateTimeOffsetCustomizer(Clock clock) {
        return builder -> builder.addModule(
                new SimpleModule("morakLocalDateTimeOffset")
                        .addSerializer(LocalDateTime.class, new ValueSerializer<LocalDateTime>() {
                            @Override
                            public void serialize(LocalDateTime value, JsonGenerator gen,
                                                  SerializationContext ctxt) {
                                gen.writeString(value.atZone(clock.getZone())
                                        .toOffsetDateTime()
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                            }
                        }));
    }
}
