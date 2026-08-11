package com.morak.proof.type;

// SCREENING·HOLD·BLOCKED는 daily_slot을 점유하지 않는다(그날 인증이 잠기지 않게).
public enum ProofAiStatus {
    SCREENING, APPROVED, PENDING_REVIEW, HOLD, BLOCKED
}
