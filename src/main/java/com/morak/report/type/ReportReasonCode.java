package com.morak.report.type;

// severity 자동 분류: SEXUAL_CONTENT·VIOLENT_THREAT·INAPPROPRIATE_SCREEN → HIGH (RP-1 2절)
public enum ReportReasonCode {
    SEXUAL_CONTENT, VIOLENT_THREAT, AD_SPAM, INAPPROPRIATE_SCREEN, ETC;

    public ReportSeverity toSeverity() {
        return switch (this) {
            case SEXUAL_CONTENT, VIOLENT_THREAT, INAPPROPRIATE_SCREEN -> ReportSeverity.HIGH;
            default -> ReportSeverity.NORMAL;
        };
    }
}
