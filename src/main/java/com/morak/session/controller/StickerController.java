package com.morak.session.controller;

import com.morak.session.dto.response.StickerListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SS-11 스티커 종류 목록. 서비스를 두지 않은 이유는 읽을 상태가 없기 때문이다 —
 * 목록은 enum 그 자체이고 스티커 전송·저장은 서버를 거치지 않는다(D17).
 */
@RestController
@RequestMapping("/api/stickers")
public class StickerController {

    @GetMapping
    public StickerListResponse getStickers() {
        return StickerListResponse.ofAll();
    }
}
