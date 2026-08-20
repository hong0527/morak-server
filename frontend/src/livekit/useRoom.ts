/**
 * LiveKit 연결. 캠 영상은 서버가 아니라 여기서 온다.
 *
 * **카메라를 잡는 곳은 이 파일 하나다.** 두 번 잡으면 기기에 따라 화면이 멈춘다
 * (open-decisions C-2 ⑦). 감지 모듈은 getUserMedia 를 부르지 않고 여기서 만든
 * <video> 를 받아 쓴다.
 *
 * **로컬에 LiveKit 서버가 없다**(frontend-guide §6-4). dev 설정의 host 가
 * ws://localhost:7880 인데 그 서버를 아직 아무도 띄우지 않았다. 그래서 연결이 실패하면
 * 카메라만 직접 열어 화면과 감지가 돌아가게 두고, 남의 캠은 비운다.
 * LiveKit 이 붙는 순간 이 대체 경로는 지나가고 로컬 트랙을 그대로 쓴다.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import {
  ConnectionState,
  RemoteTrack,
  RemoteTrackPublication,
  Room,
  RoomEvent,
  Track,
  type RemoteParticipant,
} from "livekit-client";

import { api } from "../api/client";

export interface RemoteCam {
  /** LiveKit identity 는 memberId 의 문자열이다. SS-1 참가자와 이 값으로 맞춘다 */
  identity: string;
  track: RemoteTrack;
}

export type RoomPhase = "IDLE" | "CONNECTING" | "CONNECTED" | "CAMERA_ONLY" | "FAILED";

export interface SessionRoom {
  phase: RoomPhase;
  /** 로컬 캠이 붙은 video. 감지 모듈에 그대로 넘긴다 */
  localVideoRef: React.RefObject<HTMLVideoElement | null>;
  cameraReady: boolean;
  remoteCams: RemoteCam[];
  error: string | null;
  /** 퇴출·자율 퇴장에서 부른다. 서버가 캠을 끊어 주지 않으므로 화면이 직접 끊는다(§6-3) */
  disconnect: () => void;
  /**
   * 데이터 채널 발신(D17: 스티커는 서버를 거치지 않는다). LiveKit 미연결이면
   * 보내지 않고 false 를 돌려준다 — CAMERA_ONLY 대체 경로에는 채널이 없다.
   */
  publishData: (payload: Uint8Array<ArrayBuffer>) => boolean;
  /** DataReceived 구독. 반환된 함수로 해제한다. 자기 발신은 돌아오지 않는다 */
  subscribeData: (handler: DataHandler) => () => void;
}

type DataHandler = (payload: Uint8Array, senderIdentity: string) => void;

export function useSessionRoom(sessionId: number | null): SessionRoom {
  const [phase, setPhase] = useState<RoomPhase>("IDLE");
  const [cameraReady, setCameraReady] = useState(false);
  const [remoteCams, setRemoteCams] = useState<RemoteCam[]>([]);
  const [error, setError] = useState<string | null>(null);

  const localVideoRef = useRef<HTMLVideoElement | null>(null);
  const roomRef = useRef<Room | null>(null);
  const fallbackStreamRef = useRef<MediaStream | null>(null);
  const dataHandlersRef = useRef<Set<DataHandler>>(new Set());

  const publishData = useCallback((payload: Uint8Array<ArrayBuffer>): boolean => {
    const room = roomRef.current;
    if (!room || room.state !== ConnectionState.Connected) return false;
    // 스티커는 놓쳐도 다시 보내면 되지만, 몇 개 안 되는 이벤트라 reliable 로 보낸다.
    void room.localParticipant.publishData(payload, { reliable: true });
    return true;
  }, []);

  const subscribeData = useCallback((handler: DataHandler) => {
    dataHandlersRef.current.add(handler);
    return () => {
      dataHandlersRef.current.delete(handler);
    };
  }, []);

  const disconnect = useCallback(() => {
    roomRef.current?.disconnect();
    roomRef.current = null;
    for (const track of fallbackStreamRef.current?.getTracks() ?? []) track.stop();
    fallbackStreamRef.current = null;
    setCameraReady(false);
    setRemoteCams([]);
    setPhase("IDLE");
  }, []);

  useEffect(() => {
    if (sessionId === null) return;
    let disposed = false;

    const attachLocal = (stream: MediaStream) => {
      const el = localVideoRef.current;
      if (!el) return;
      el.srcObject = stream;
      el.muted = true;
      void el.play().catch(() => {
        // 자동 재생이 막히면 사용자가 화면을 한 번 누른 뒤 다시 시도된다.
      });
      setCameraReady(true);
    };

    void (async () => {
      setPhase("CONNECTING");
      try {
        // 저장해 둔 토큰을 재사용하지 않는다. 수명이 1시간인데 세션은 240분까지 있어
        // 3시간째에 새로고침하면 처음 받은 토큰은 이미 만료돼 있다(§⑤).
        const access = await api.session.issueToken(sessionId);
        if (disposed) return;

        const room = new Room({ adaptiveStream: true, dynacast: true });
        roomRef.current = room;

        room.on(RoomEvent.TrackSubscribed, (track: RemoteTrack, _pub: RemoteTrackPublication, participant: RemoteParticipant) => {
          if (track.kind !== Track.Kind.Video) return;
          setRemoteCams((prev) => [
            ...prev.filter((c) => c.identity !== participant.identity),
            { identity: participant.identity, track },
          ]);
        });
        room.on(RoomEvent.TrackUnsubscribed, (_t, _p, participant: RemoteParticipant) => {
          setRemoteCams((prev) => prev.filter((c) => c.identity !== participant.identity));
        });
        room.on(RoomEvent.ParticipantDisconnected, (participant: RemoteParticipant) => {
          setRemoteCams((prev) => prev.filter((c) => c.identity !== participant.identity));
        });
        room.on(RoomEvent.DataReceived, (payload: Uint8Array, participant?: RemoteParticipant) => {
          // identity 는 memberId 의 문자열이다(SS-1 참가자와 같은 규약).
          for (const handler of dataHandlersRef.current) {
            handler(payload, participant?.identity ?? "");
          }
        });
        room.on(RoomEvent.ConnectionStateChanged, (s: ConnectionState) => {
          // 끊기면 서버가 90초를 기다린다. 넘기면 LEFT(DEVICE_ISSUE) 로 정산되고 되돌릴 수 없다.
          // livekit-client 가 그 안에서 알아서 재연결하지만, 실패하면 사용자에게 알려야 한다.
          if (s === ConnectionState.Disconnected) {
            setError("연결이 끊겼어요. 90초 안에 다시 연결되지 않으면 완주로 인정되지 않아요.");
          } else if (s === ConnectionState.Connected) {
            setError(null);
          }
        });

        await room.connect(access.url, access.token);
        if (disposed) {
          room.disconnect();
          return;
        }

        // 마이크는 켜지 않는다. canPublishAudio 가 항상 false 다.
        await room.localParticipant.setCameraEnabled(true);
        const pub = room.localParticipant.getTrackPublication(Track.Source.Camera);
        const track = pub?.track?.mediaStreamTrack;
        if (track) attachLocal(new MediaStream([track]));
        setPhase("CONNECTED");
      } catch (e) {
        if (disposed) return;
        // LiveKit 이 없어도 감지와 화면은 확인할 수 있어야 한다. 카메라만 직접 연다.
        // 여기서도 getUserMedia 는 딱 한 번이라 "두 번 잡지 마라"는 규약을 어기지 않는다.
        try {
          const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
          if (disposed) {
            for (const t of stream.getTracks()) t.stop();
            return;
          }
          fallbackStreamRef.current = stream;
          attachLocal(stream);
          setPhase("CAMERA_ONLY");
          // raw error.message 는 사용자에게 보여줄 문구가 아니다. 원문은 콘솔로 남긴다.
          console.warn("LiveKit connect failed:", e);
          setError("실시간 연결에 실패했어요. 내 캠 분석은 계속되지만 다른 참가자의 캠은 보이지 않아요.");
        } catch (camError) {
          setPhase("FAILED");
          console.warn("camera open failed:", camError);
          setError("카메라를 열 수 없습니다. 브라우저의 카메라 권한을 확인해 주세요.");
        }
      }
    })();

    return () => {
      disposed = true;
      disconnect();
    };
  }, [sessionId, disconnect]);

  return { phase, localVideoRef, cameraReady, remoteCams, error, disconnect, publishData, subscribeData };
}
