/**
 * 생성된 스키마(schema.gen.ts)에 읽기 좋은 이름을 붙인 것.
 *
 * 화면 코드가 components["schemas"]["..."] 를 직접 쓰지 않게 하려는 것이 목적이다.
 * 타입 본체는 openapi.yaml 이 소유하므로 서버가 필드를 바꾸면 gen:api 한 번으로
 * 여기 전부가 따라 바뀌고, 안 맞는 화면은 타입 검사에서 걸린다.
 */
import type { components } from "./schema.gen";

type S = components["schemas"];

// 공통
export type ErrorBody = S["ErrorResponse"];
export type PageMeta = S["PageMeta"];

// 인증 · 회원
export type LoginRequest = S["LoginRequest"];
export type LoginResponse = S["LoginResponse"];
export type AgreementItem = S["AgreementItem"];
export type MemberMe = S["MemberMeResponse"];
export type ActiveSessionSummary = S["ActiveSessionSummary"];
export type Goal = S["GoalResponse"];
export type StreakSummary = S["StreakSummary"];
export type SanctionSummary = S["SanctionSummary"];
export type AgeVerificationResponse = S["AgeVerificationResponse"];
export type WithdrawalResponse = S["WithdrawalResponse"];

// 매칭
export type MatchRequest = S["MatchRequestResponse"];
export type MatchRequestStatus = S["MatchRequestStatus"];
export type TargetMinutes = S["TargetMinutes"];

// 세션
export type SessionDetail = S["SessionDetailResponse"];
export type SessionParticipant = S["SessionParticipant"];
export type SessionStatus = S["SessionStatus"];
export type SessionEndReason = S["SessionEndReason"];
export type ParticipantStatus = S["ParticipantStatus"];
export type LivekitToken = S["LivekitTokenResponse"];
export type SessionGoalResponse = S["SessionGoalResponse"];
export type AbsenceEventRequest = S["AbsenceEventRequest"];
export type AbsenceEventResponse = S["AbsenceEventResponse"];
export type PauseStartResponse = S["PauseStartResponse"];
export type PauseEndResponse = S["PauseEndResponse"];
export type LeaveReason = S["LeaveReason"];
export type SessionResult = S["SessionResultResponse"];
export type MySessionResult = S["MySessionResult"];
export type MyWarningItem = S["MyWarningItem"];
export type SessionResultParticipant = S["SessionResultParticipant"];
export type MySessionPage = S["MySessionPageResponse"];
export type StickerItem = S["StickerItem"];

// 이의 신청
export type AppealResponse = S["AppealResponse"];
export type MyAppealPage = S["MyAppealPageResponse"];

// 포인트 · 스토어 · 결제
export type PointBalance = S["PointBalanceResponse"];
export type PointLedgerItem = S["PointLedgerItem"];
export type ProductPage = S["ProductPageResponse"];
export type ProductDetail = S["ProductDetail"];
export type OrderPage = S["OrderPageResponse"];
export type OrderDetail = S["OrderDetail"];
export type OrderCreateResponse = S["OrderCreateResponse"];
export type ChargeCreateResponse = S["ChargeCreateResponse"];
export type ChargeConfirmResponse = S["ChargeConfirmResponse"];

// 신고
export type ReportCreateResponse = S["ReportCreateResponse"];
export type ReportReasonCode = S["ReportReasonCode"];
export type ReportTargetType = S["ReportTargetType"];

// 관리자
export type ReportCasePage = S["ReportCasePageResponse"];
export type ReportCaseDetail = S["ReportCaseDetail"];
export type ReportProcessResponse = S["ReportProcessResponse"];
export type SanctionResponse = S["SanctionResponse"];
export type AppealPage = S["AppealPageResponse"];
export type AppealDetail = S["AppealDetailResponse"];
export type AppealProcessResponse = S["AppealProcessResponse"];
export type AdminSessionPage = S["AdminSessionPageResponse"];
export type WithdrawalPage = S["WithdrawalPageResponse"];

// 개발 전용 (dev 프로필에서만 등록된다)
export type DevClockResponse = S["DevClockResponse"];
export type DevSessionSeedResponse = S["DevSessionSeedResponse"];
export type DevBatchResponse = S["DevBatchResponse"];
export type DevBatchName = S["DevBatchName"];
