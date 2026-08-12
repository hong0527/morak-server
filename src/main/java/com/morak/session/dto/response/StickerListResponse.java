package com.morak.session.dto.response;

import com.morak.session.type.StickerType;
import java.util.Arrays;
import java.util.List;

/**
 * SS-11 스티커 종류 목록. 서버가 하는 일은 종류를 알려주는 것뿐이고, 전송은 LiveKit
 * 데이터 채널로 클라이언트끼리 직접 오간다. 서버는 스티커를 저장하지 않는다(D17).
 */
public record StickerListResponse(List<Sticker> stickers) {

    public record Sticker(StickerType type, String label) {
    }

    public static StickerListResponse ofAll() {
        return new StickerListResponse(Arrays.stream(StickerType.values())
                .map(type -> new Sticker(type, type.getLabel()))
                .toList());
    }
}
