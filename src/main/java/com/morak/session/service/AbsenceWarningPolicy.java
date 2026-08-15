package com.morak.session.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 자리비움 구간 하나가 경고 몇 회인지를 정하는 단 하나의 자리.
 *
 * <p><b>길이를 세지 않으면 판정이 뒤집힌다.</b> 구간 하나당 최대 1회이던 규칙에서는
 * 60분 세션에서 45분을 비운 사람이 경고 1회로 완주해 포인트와 Streak을 받고, 61초짜리 물
 * 마시기를 세 번 한 사람이 퇴출에 −300P를 물었다. 자리비움 총량이 15배 차이 나는데 결과가
 * 정반대였다 — 그 규칙은 <b>지속시간이 아니라 빈도를 처벌</b>하고 있었고 FR-303·FR-304의
 * 취지와 반대다.
 *
 * <p>클래스를 따로 두는 이유는 하나다 — <b>경고를 만드는 자리가 셋인데 계산이 갈리면 안
 * 된다.</b> SS-4 판정, SS-5 Pause 시작 마감, B1 종료 정산이 모두 여기를 부른다.
 * (SS-6 Pause 초과 복귀는 자리비움이 아니라 화장실 상한 축이라 여기 오지 않는다. D9)
 */
@Component
public class AbsenceWarningPolicy {

    private final int thresholdSeconds;
    private final int escalationSeconds;

    public AbsenceWarningPolicy(
            @Value("${morak.session.absence-threshold-seconds}") int thresholdSeconds,
            @Value("${morak.session.absence-warning-escalation-seconds}") int escalationSeconds) {
        this.thresholdSeconds = thresholdSeconds;
        this.escalationSeconds = escalationSeconds;
    }

    /**
     * 이 길이의 자리비움 구간이 만드는 경고 수. 임계 이하면 0이다.
     *
     * <p>첫 경고 기준은 그대로 "임계 초과"이고, 그 뒤로는 <b>임계와 다른 눈금</b>으로 하나씩
     * 쌓는다. 눈금을 임계와 같게 두면 3분 남짓 한 번에 곧바로 퇴출인데, 택배·전화처럼 흔하고
     * 악의 없는 이석이 그 길이다. 자발적으로 쓰는 서비스에서 첫 사건에 −300P와 재매칭 금지를
     * 물리면 단계적 경고(D11)라는 설계가 통째로 사라진다.
     *
     * <p>눈금을 넓게 두면 <b>한 구간만으로 퇴출되는 지점이 화장실 모드 상한(10분)보다 뒤로</b>
     * 밀린다. 이 서비스가 "잠깐 자리를 비워도 되는 시간"으로 스스로 정해 둔 값이 10분이므로,
     * 그보다 오래 사라진 구간을 퇴출로 보는 것은 설명할 수 있는 선이다.
     *
     * <p>등급이 갈리는 계단은 첫 자리(임계 앞뒤 1초)에만 있고 그것은 기존 규칙 그대로다.
     * 그 뒤 계단은 눈금만큼 벌어져 있어 훨씬 완만하다.
     *
     * <p>총량이 아니라 <b>구간별</b>로 센다. 임계 이하의 짧은 이석을 무한히 반복하는 구멍은
     * 그대로 남는데, 그것은 "임계를 넘겨야 경고"라는 기존 기준의 성질이라 여기서 다루지 않는다.
     */
    public int warningCountFor(long absentSeconds) {
        if (absentSeconds <= thresholdSeconds) {
            return 0;
        }
        long beyond = absentSeconds - thresholdSeconds - 1;
        return 1 + (int) (beyond / escalationSeconds);
    }
}
