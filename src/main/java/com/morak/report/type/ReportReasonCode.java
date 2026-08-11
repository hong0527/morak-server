package com.morak.report.type;

// severity 자동 분류: INAPPROPRIATE_CONTENT·PRIVACY_VIOLATION·ABUSIVE_LANGUAGE → HIGH
public enum ReportReasonCode {
    INAPPROPRIATE_CONTENT, ABUSIVE_LANGUAGE, SPAM_PROOF, FAKE_PROOF, PRIVACY_VIOLATION, ETC;

    public ReportSeverity toSeverity() {
        return switch (this) {
            case INAPPROPRIATE_CONTENT, PRIVACY_VIOLATION, ABUSIVE_LANGUAGE -> ReportSeverity.HIGH;
            default -> ReportSeverity.NORMAL;
        };
    }
}
