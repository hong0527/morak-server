package com.morak.session.service;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SS-2 접속 토큰 서명. 서버는 토큰만 발급하고 미디어는 경유하지 않는다.
 *
 * <p><b>identity = memberId 문자열</b>이라는 규약이 이 클래스와 {@link LiveKitWebhookService}
 * 양쪽에 걸려 있다(blueprint §10.5). 별도 매핑 컬럼을 두지 않으므로 이 규칙을 바꾸면 토큰
 * 발급과 웹훅 참가자 조회가 함께 깨진다. 그래서 문자열 변환을 두 곳에 흩지 않고
 * {@link #identityOf}·{@link LiveKitWebhookService}가 같은 규칙을 공유한다.
 *
 * <p><b>마이크는 여기서 막는다(D23).</b> publish 권한을 카메라 소스로 좁혀 오디오 트랙을
 * 올릴 수단 자체를 없앤다. 클라이언트 UI로 막으면 앱을 고친 사람은 언뮤트할 수 있다.
 * 룸 생성·관리 권한({@code RoomCreate}·{@code RoomAdmin})은 주지 않는다 — 참가자 토큰으로
 * 남을 강제 퇴장시킬 수 있으면 안 된다.
 */
@Component
public class LiveKitTokenProvider {

    /** LiveKit이 정의한 트랙 소스 이름. 카메라만 허용하면 마이크·화면공유가 함께 막힌다. */
    private static final String SOURCE_CAMERA = "camera";

    private static final long MILLIS_PER_SECOND = 1000L;

    private final String host;
    private final String apiKey;
    private final String apiSecret;
    private final int tokenTtlSeconds;

    public LiveKitTokenProvider(@Value("${morak.livekit.host}") String host,
                                @Value("${morak.livekit.api-key}") String apiKey,
                                @Value("${morak.livekit.api-secret}") String apiSecret,
                                @Value("${morak.livekit.token-ttl-seconds}") int tokenTtlSeconds) {
        this.host = host;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public static String identityOf(Long memberId) {
        return String.valueOf(memberId);
    }

    public String issue(Long memberId, String roomName) {
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setIdentity(identityOf(memberId));
        // SDK의 ttl 단위는 밀리초다(기본값이 TimeUnit.MILLISECONDS.convert(6, HOURS)).
        // 초로 넣으면 토큰이 3.6초 만에 만료된다.
        token.setTtl(tokenTtlSeconds * MILLIS_PER_SECOND);
        token.addGrants(
                new RoomJoin(true),
                new RoomName(roomName),
                new CanPublish(true),
                new CanPublishSources(List.of(SOURCE_CAMERA)),
                new CanSubscribe(true));
        // canPublishData는 넣지 않는다. LiveKit은 이 값이 없으면 허용으로 보고, 스티커(SS-11)가
        // 그 데이터 채널을 쓴다. 명세가 정한 grant 목록에 없는 값을 굳이 박지 않는다.
        return token.toJwt();
    }

    public String getHost() {
        return host;
    }

    public int getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }
}
