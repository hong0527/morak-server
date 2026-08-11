package com.morak.report.type;

// 유효 제재 = starts_at <= now AND (ends_at IS NULL OR ends_at > now)
public enum SanctionType {
    TEMP, PERMANENT
}
