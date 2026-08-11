package com.morak.common.type;

// 완주 배지 — 저장하지 않는 파생값. completed=false면 무조건 NONE.
public enum BadgeCode {
    GOLD, SILVER, BRONZE, NONE;

    public static BadgeCode of(boolean completed, double proofRate) {
        if (!completed) {
            return NONE;
        }
        if (proofRate >= 0.95) {
            return GOLD;
        }
        if (proofRate >= 0.85) {
            return SILVER;
        }
        return BRONZE;
    }
}
