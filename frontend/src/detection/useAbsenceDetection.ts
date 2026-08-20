/**
 * 감지 모듈(ai-detection)과 세션 화면을 잇는 유일한 자리.
 *
 * 화면은 이 훅만 쓰고 AbsenceDetectionController 를 직접 만지지 않는다.
 * ai-detection/README 4절의 "앱이 부르지 않으면 조용히 깨지는 것 셋"을 화면이
 * 기억하지 않아도 되게 하려는 것이 목적이라, 셋 다 여기서 구조적으로 막았다.
 *
 *   ① sessionStartedAtMs 를 안 넘긴다 → 인자로 필수다. 안 넘기면 타입 검사가 막는다
 *   ② 401 뒤 resumeAfterReauth() 를 안 부른다 → 토큰 저장소를 구독해 자동으로 부른다
 *   ③ SS-5 성공 뒤 onRestroomStarted(응답) 를 안 부른다 → SS-5 호출 자체를 이 훅이 갖는다.
 *      화면은 startRestroom() 을 부르고, 그 안에서 순서가 지켜진다
 *
 * ③ 이 이 훅에 API 호출을 들인 이유다. 화면이 api.session.startPause() 를 직접 부를 수
 * 있게 두면 감지를 멈추는 호출을 빠뜨려도 아무 경고가 없고, 화장실에 있던 시간이
 * 그대로 자리비움 경고가 된다. 실측에서 실제로 깨진 경로다(9-12).
 */
import { useCallback, useEffect, useRef, useState } from "react";
import {
  AbsenceDetectionController,
  fromPauseStart,
} from "molock-ai-detection/dist/src/client/controller.js";
import { FetchAbsenceTransport } from "molock-ai-detection/dist/src/client/sender.js";
import type { CalibrationWarning } from "molock-ai-detection/dist/src/calibration.js";
import type { AbsenceEventResponse } from "molock-ai-detection/dist/src/types.js";

import { api } from "../api/client";
import { authStore } from "../auth/session";
import type { PauseEndResponse, PauseStartResponse } from "../api/types";
import { DETECTION_ASSETS, WORKER_URL } from "./assets";

export type DetectionPhase =
  | "IDLE"
  | "LOADING_MODEL"
  | "CALIBRATING"
  | "RUNNING"
  | "PAUSED"
  | "STOPPED"
  | "FAILED";

export interface AbsenceDetectionOptions {
  sessionId: number;
  /** SS-1 의 startedAt. 이것이 없으면 탭을 닫았다 들어온 사용자의 감지가 통째로 꺼진다(9-10) */
  sessionStartedAt: string;
  memberId: number;
  /** LiveKit 로컬 트랙이 이미 붙어 있는 video. 카메라를 새로 열지 않는다 */
  videoRef: React.RefObject<HTMLVideoElement | null>;
  /** 카메라가 준비되기 전에는 false. 준비되면 true 로 바꾼다 */
  enabled: boolean;
  /** 퇴출됐다. 화면은 LiveKit 을 끊고 결과 화면으로 보낸다 — 서버가 캠을 끊어 주지 않는다(§6-3) */
  onEvicted: (evictionId: number | null) => void;
}

export interface AbsenceDetection {
  phase: DetectionPhase;
  /** "자리에 계신가요?" 프롬프트. 탭하면 tapPrompt() 를 부른다 */
  promptVisible: boolean;
  tapPrompt: () => void;
  /** 카메라 각도·조명 조정 안내. 3회 연속 프롬프트가 해소되지 않았을 때 */
  adjustHint: boolean;
  /** 서버가 센 내 경고 수. 폴링으로 알아내는 값이 아니라 내 호출의 응답에 실려 온다 */
  warningCount: number;
  calibrationWarnings: CalibrationWarning[];
  error: string | null;
  /** 화장실 모드. SS-5 를 부르고 감지를 멈추는 것까지 한 묶음이다 */
  startRestroom: () => Promise<PauseStartResponse>;
  endRestroom: () => Promise<PauseEndResponse>;
}

export function useAbsenceDetection(options: AbsenceDetectionOptions): AbsenceDetection {
  const { sessionId, sessionStartedAt, memberId, videoRef, enabled, onEvicted } = options;

  const [phase, setPhase] = useState<DetectionPhase>("IDLE");
  const [promptVisible, setPromptVisible] = useState(false);
  const [adjustHint, setAdjustHint] = useState(false);
  const [warningCount, setWarningCount] = useState(0);
  const [calibrationWarnings, setCalibrationWarnings] = useState<CalibrationWarning[]>([]);
  const [error, setError] = useState<string | null>(null);

  const controllerRef = useRef<AbsenceDetectionController | null>(null);
  // 훅이 바뀌어도 콜백은 최신 것을 보게 한다. 컨트롤러는 한 번만 만들기 때문이다.
  const onEvictedRef = useRef(onEvicted);
  onEvictedRef.current = onEvicted;

  const applyVerdict = useCallback((res: AbsenceEventResponse) => {
    setWarningCount(res.warningCount);
    if (res.evicted) onEvictedRef.current(res.evictionId);
  }, []);

  useEffect(() => {
    if (!enabled) return;
    const video = videoRef.current;
    if (!video) return;

    let disposed = false;

    const controller = new AbsenceDetectionController({
      video,
      sessionId,
      // 저장소가 비어도 clientSeq 가 앞 실행과 겹치지 않게 하는 바닥값이다(9-10).
      sessionStartedAtMs: Date.parse(sessionStartedAt),
      memberId,
      createWorker: () => new Worker(WORKER_URL),
      assets: DETECTION_ASSETS,
      transport: new FetchAbsenceTransport(
        `/api/sessions/${sessionId}/absence-events`,
        (): Record<string, string> => {
          const token = authStore.get().accessToken;
          return token ? { Authorization: `Bearer ${token}` } : {};
        },
      ),
      hooks: {
        onShowPrompt: () => setPromptVisible(true),
        onHidePrompt: () => {
          setPromptVisible(false);
          setAdjustHint(false);
        },
        onShowAdjustHint: () => setAdjustHint(true),
        onServerVerdict: applyVerdict,
        onTerminal: () => {
          setPhase("STOPPED");
          setError("자리 비움 감지가 중단됐어요. 화면을 새로고침하면 다시 시작돼요.");
        },
        // 감지는 계속 돌고 이벤트는 큐에 쌓인다. 재로그인은 아래 토큰 구독이 이어받는다.
        onAuthExpired: () => setError("로그인이 만료됐어요. 다시 로그인하면 이어서 기록돼요."),
        onError: (message, fatal) => {
          setError(message);
          if (fatal) setPhase("FAILED");
        },
      },
    });
    controllerRef.current = controller;

    void (async () => {
      try {
        setPhase("LOADING_MODEL");
        await controller.init();
        if (disposed) return;

        setPhase("CALIBRATING");
        const outcome = await controller.calibrate();
        if (disposed) return;
        setCalibrationWarnings(outcome.warnings);
        // 조명이 부족해도 입장을 막지 않는다. 안내만 한다.

        controller.start();
        setPhase("RUNNING");
      } catch (e) {
        if (disposed) return;
        setPhase("FAILED");
        setError(e instanceof Error ? e.message : String(e));
      }
    })();

    return () => {
      disposed = true;
      controllerRef.current = null;
      controller.dispose();
    };
  }, [enabled, sessionId, sessionStartedAt, memberId, videoRef, applyVerdict]);

  // 401 로 전송이 멈춘 뒤 다시 로그인하면 쌓인 큐를 풀어 준다.
  // 이것을 부르지 않으면 세션이 끝날 때까지 하나도 나가지 않는다(9-11).
  useEffect(() => {
    let previous = authStore.get().accessToken;
    return authStore.subscribe((state) => {
      const renewed = state.accessToken !== null && state.accessToken !== previous;
      previous = state.accessToken;
      if (renewed) {
        controllerRef.current?.resumeAfterReauth();
        setError(null);
      }
    });
  }, []);

  const tapPrompt = useCallback(() => {
    controllerRef.current?.tapPrompt();
    setPromptVisible(false);
  }, []);

  /**
   * SS-5 가 200 으로 성공한 "뒤에" 감지를 멈춘다. 순서가 뒤집히면 안 된다 —
   * 먼저 멈추고 SS-5 가 409(PAUSE_ALREADY_USED·ALREADY_EVICTED)로 실패하면
   * 감지가 꺼진 채 세션이 계속된다.
   *
   * 응답을 함께 넘기는 이유는, 화장실 모드를 시작할 때 진행 중이던 자리비움이 먼저 닫히며
   * 경고가 붙을 수 있고 그 사실이 SS-5 응답에만 실리기 때문이다(9-12).
   */
  const startRestroom = useCallback(async (): Promise<PauseStartResponse> => {
    const res = await api.session.startPause(sessionId);
    // 감지 모듈은 closedAbsenceSeconds 를 null 까지만 받는다. 명세에서 이 필드가
    // 선택이라 undefined 로 올 수 있어 여기서 한 번 맞춘다.
    const verdict = { ...res, closedAbsenceSeconds: res.closedAbsenceSeconds ?? null };
    controllerRef.current?.onRestroomStarted(verdict);
    applyVerdict(fromPauseStart(verdict));
    setPhase("PAUSED");
    return res;
  }, [sessionId, applyVerdict]);

  const endRestroom = useCallback(async (): Promise<PauseEndResponse> => {
    const res = await api.session.endPause(sessionId);
    controllerRef.current?.onRestroomEnded();
    setPhase("RUNNING");
    setWarningCount(res.warningCount);
    // 상한을 넘겨 복귀하면 여기서 퇴출이 온다. ACTIVE 만 온다고 단정하면 안 된다.
    if (res.evicted) onEvictedRef.current(null);
    return res;
  }, [sessionId]);

  return {
    phase,
    promptVisible,
    tapPrompt,
    adjustHint,
    warningCount,
    calibrationWarnings,
    error,
    startRestroom,
    endRestroom,
  };
}
