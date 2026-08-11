package com.morak.ai.type;

// 대상 행이 아직 없는 판정(얼굴 선검사·텍스트 검열)은 MEMBER로 기록
public enum AiTargetType {
    PROOF, MEMBER, GROUP
}
