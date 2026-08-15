package com.morak.session.type;

// SS-11 목록 응답 전용. 스티커 전송은 LiveKit 데이터 채널로 오가고 서버는 저장하지 않는다(D17).
// 라벨을 여기 두는 이유는 목록이 정적이기 때문이다 — 종류와 문구가 갈라지면 클라이언트가
// 모르는 type을 받는 순간 그릴 그림이 없어진다.
public enum StickerType {

    CLAP("파이팅"),
    MUSCLE("힘내요"),
    FIRE("열공");

    private final String label;

    StickerType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
