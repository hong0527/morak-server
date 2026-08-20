const screenNames = {
  landing: "랜딩페이지",
  login: "온보딩",
  birth: "생년월일",
  mediaConsent: "촬영물 동의",
  home: "홈화면",
  match: "매칭조건",
  matching: "매칭 중",
  matchAdjust: "조건 조정",
  matched: "매칭 완료",
  replenishJoin: "공식 충원 합류 안내",
  group: "챌린지 그룹 화면",
  leaveGroup: "자율 퇴장",
  submit: "오늘의 인증 제출",
  feed: "오늘의 인증 현황",
  rejected: "인증 반려 확인",
  report: "신고하기",
  reportDone: "신고 접수 완료",
  reportTab: "결과 리포트",
  missed: "정식 완주 기준 미달",
  profile: "프로필",
  manner: "매너점수",
  leave: "계정 탈퇴",
  leaveDone: "탈퇴 신청 완료",
};

const tabs = [
  ["home", "홈", "home"],
  ["match", "매칭", "match"],
  ["group", "그룹", "group"],
  ["reportTab", "리포트", "report"],
  ["profile", "내 정보", "profile"],
];

const icons = {
  home: '<svg viewBox="0 0 24 24"><path d="M3 10.5 12 3l9 7.5v9.5h-6v-6H9v6H3z"/></svg>',
  match: '<svg viewBox="0 0 24 24"><circle cx="8" cy="8" r="3"/><circle cx="16" cy="9" r="2.5"/><circle cx="12" cy="16" r="3"/><path d="M9.5 10.5 11 13M14.2 10.8 13 13"/></svg>',
  group: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3"/><circle cx="6" cy="10" r="2.3"/><circle cx="18" cy="10" r="2.3"/><path d="M5 19c.6-3 3-5 7-5s6.4 2 7 5M2.8 18c.4-2 1.7-3.3 3.8-3.8M21.2 18c-.4-2-1.7-3.3-3.8-3.8"/></svg>',
  report: '<svg viewBox="0 0 24 24"><path d="M6 3h9l3 3v15H6z" fill="none"/><path d="M14 3v5h4M8 12h8M8 16h8"/></svg>',
  profile: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3"/><path d="M5 20c.7-4 3-6 7-6s6.3 2 7 6z"/></svg>',
};

const state = {
  current: "landing",
  history: [],
  proofSubmitted: false,
  photoSelected: false,
  matchTimer: null,
  splashTimer: null,
  toastTimer: null,
  modalOk: null,
};

function currentTab(screen) {
  if (["matching", "matchAdjust", "matched"].includes(screen)) return "match";
  if (["submit", "feed", "report", "reportDone", "rejected", "leaveGroup"].includes(screen)) return "group";
  if (["missed"].includes(screen)) return "reportTab";
  return screen;
}

function renderTabs(activeScreen) {
  document.querySelectorAll(".tab").forEach((tab) => {
    tab.innerHTML = tabs.map(([target, label, icon]) => `
      <button class="${currentTab(activeScreen) === target ? "active" : ""}" data-go="${target}" aria-label="${label}">
        ${icons[icon]}
        <span>${label}</span>
      </button>
    `).join("");
  });
}

function showToast(message) {
  const toast = document.querySelector("#toast");
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(() => toast.classList.remove("show"), 1700);
}

function openModal({ title, body, okText = "확인", cancelText = "취소", onOk }) {
  document.querySelector("#modalTitle").textContent = title;
  document.querySelector("#modalBody").textContent = body;
  document.querySelector("#modalOk").textContent = okText;
  document.querySelector("#modalCancel").textContent = cancelText;
  state.modalOk = onOk;
  document.querySelector("#modal").classList.remove("hidden");
}

function closeModal() {
  document.querySelector("#modal").classList.add("hidden");
  state.modalOk = null;
}

function stopMatching() {
  clearInterval(state.matchTimer);
  state.matchTimer = null;
}

function scheduleSplashTransition() {
  clearTimeout(state.splashTimer);
  state.splashTimer = setTimeout(() => {
    if (state.current === "landing") {
      showToast("모락에 오신 것을 환영합니다.");
      go("login");
    }
  }, 2000);
}

function startMatching() {
  stopMatching();
  const count = document.querySelector("#matchCount");
  const bar = document.querySelector("#matchBar");
  const eta = document.querySelector("#matchEta");
  let step = 1;
  const values = [1, 2, 4, 5, 6];
  count.textContent = "1 / 6명";
  bar.style.width = "16%";
  eta.textContent = "3초 내 자동 완료";
  state.matchTimer = setInterval(() => {
    const value = values[step];
    count.textContent = `${value} / 6명`;
    bar.style.width = `${Math.round((value / 6) * 100)}%`;
    eta.textContent = value === 6 ? "매칭 완료" : `${3 - step}초 남음`;
    step += 1;
    if (value === 6) {
      stopMatching();
      setTimeout(() => {
        showToast("6인 그룹 매칭이 완료되었습니다.");
        go("matched");
      }, 450);
    }
  }, 700);
}

function syncProofState() {
  const todayStatus = document.querySelector("#todayStatus");
  const submitEntry = document.querySelector("#submitEntry");
  const mineProof = document.querySelector("#mineProof");
  if (state.proofSubmitted) {
    todayStatus.innerHTML = '오늘 인증을 제출했어요<br><b>목표 운동 1시간 완료</b>';
    submitEntry.textContent = "인증 현황 보기";
    submitEntry.dataset.go = "feed";
    mineProof.classList.remove("hidden");
  } else {
    todayStatus.innerHTML = '아직 오늘 인증을 제출하지 않았어요<br><b>목표 운동 1시간</b>';
    submitEntry.textContent = "인증 제출하기";
    submitEntry.dataset.go = "submit";
    mineProof.classList.add("hidden");
  }
}

function resetBirthMessage() {
  const message = document.querySelector("#birthMessage");
  message.textContent = "만 14세 이상만 이용 가능합니다.";
  message.className = "hint";
}

function go(screen, pushHistory = true) {
  if (pushHistory && state.current !== screen) state.history.push(state.current);
  state.current = screen;
  if (screen !== "landing") clearTimeout(state.splashTimer);
  if (screen !== "matching") stopMatching();
  document.querySelectorAll(".screen").forEach((el) => {
    el.classList.toggle("active", el.dataset.screen === screen);
    if (el.dataset.screen === screen) el.scrollTop = 0;
  });
  document.querySelector("#frameName").textContent = screenNames[screen] || screen;
  renderTabs(screen);
  syncProofState();
  if (screen === "birth") resetBirthMessage();
  if (screen === "matching") startMatching();
  if (screen === "landing") scheduleSplashTransition();
}

function validateBirth() {
  const year = Number(document.querySelector("#birthYear").value);
  const month = Number(document.querySelector("#birthMonth").value);
  const day = Number(document.querySelector("#birthDay").value);
  const message = document.querySelector("#birthMessage");
  const birthday = new Date(year, month - 1, day);
  const today = new Date();
  const valid = year > 1900 && month >= 1 && month <= 12 && day >= 1 && day <= 31 &&
    birthday.getFullYear() === year && birthday.getMonth() === month - 1 && birthday.getDate() === day;
  if (!valid) {
    message.textContent = "생년월일을 정확히 입력해주세요.";
    message.className = "hint error-text";
    return;
  }
  let age = today.getFullYear() - year;
  const beforeBirthday = today.getMonth() < month - 1 || (today.getMonth() === month - 1 && today.getDate() < day);
  if (beforeBirthday) age -= 1;
  if (age < 14) {
    message.textContent = "만 14세 미만은 서비스를 이용할 수 없습니다.";
    message.className = "hint error-text";
    showToast("연령 기준을 충족하지 못했습니다.");
    return;
  }
  message.textContent = "이용 가능 연령입니다.";
  message.className = "hint success-text";
  showToast("가입 정보가 확인되었습니다.");
  setTimeout(() => go("mediaConsent"), 500);
}

document.addEventListener("click", (event) => {
  const target = event.target.closest("[data-go]");
  if (!target) return;
  go(target.dataset.go);
});

document.querySelector("#birthConfirm").addEventListener("click", validateBirth);

document.querySelector("#fastMatch").addEventListener("click", () => {
  stopMatching();
  showToast("매칭 완료 화면으로 이동합니다.");
  go("matched");
});

document.querySelector("#cancelMatch").addEventListener("click", () => {
  openModal({
    title: "매칭 요청 취소",
    body: "취소하면 현재 대기가 종료되고 조건 화면으로 이동합니다.",
    okText: "요청 취소",
    cancelText: "계속 대기",
    onOk: () => {
      closeModal();
      showToast("매칭 요청이 취소되었습니다.");
      go("match");
    },
  });
});

document.querySelector("#photoPick").addEventListener("click", () => {
  state.photoSelected = true;
  document.querySelector("#photoPick").classList.add("selected");
  document.querySelector("#photoPick").innerHTML = "✓<span>사진 선택됨</span>";
  showToast("인증 사진이 선택되었습니다.");
});

document.querySelector("#submitProof").addEventListener("click", () => {
  if (!state.photoSelected) {
    showToast("먼저 인증 사진을 선택해주세요.");
    return;
  }
  state.proofSubmitted = true;
  showToast("오늘의 인증이 제출되었습니다.");
  setTimeout(() => go("feed"), 450);
});

document.querySelector("#modalClose").addEventListener("click", closeModal);
document.querySelector("#modalCancel").addEventListener("click", closeModal);
document.querySelector("#modalOk").addEventListener("click", () => {
  if (typeof state.modalOk === "function") state.modalOk();
});

renderTabs("landing");
syncProofState();
scheduleSplashTransition();
