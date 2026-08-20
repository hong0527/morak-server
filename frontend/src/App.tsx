import { useCallback, useState } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";

import { useAuth } from "./auth/useAuth";
import LoginScreen from "./screens/LoginScreen";
import KakaoCallbackScreen from "./screens/KakaoCallbackScreen";
import BirthdateScreen from "./screens/BirthdateScreen";
import MediaConsentScreen from "./screens/MediaConsentScreen";
import HomeScreen from "./screens/HomeScreen";
import MatchWaitScreen from "./screens/MatchWaitScreen";
import SessionScreen from "./screens/SessionScreen";
import ResultScreen from "./screens/ResultScreen";
import AppealScreen from "./screens/AppealScreen";
import GoalScreen from "./screens/GoalScreen";
import PointsScreen from "./screens/PointsScreen";
import StoreScreen from "./screens/StoreScreen";
import RecordsScreen from "./screens/RecordsScreen";
import ReportScreen from "./screens/ReportScreen";
import ProfileScreen from "./screens/ProfileScreen";
import LeaveScreen from "./screens/LeaveScreen";
import AdminGuard from "./components/AdminGuard";
import AdminDashboardScreen from "./screens/AdminDashboardScreen";
import AdminReportsScreen from "./screens/AdminReportsScreen";
import AdminAppealsScreen from "./screens/AdminAppealsScreen";
import AdminSessionsScreen from "./screens/AdminSessionsScreen";
import AdminWithdrawalsScreen from "./screens/AdminWithdrawalsScreen";
import SplashScreen from "./screens/SplashScreen";

const SPLASH_KEY = "morak.splashSeen";

function shouldShowSplash(): boolean {
  if (authStoreHasToken()) return false;
  try { return window.sessionStorage.getItem(SPLASH_KEY) !== "1"; }
  catch { return true; }
}

function authStoreHasToken(): boolean {
  try { return window.localStorage.getItem("molock.accessToken") !== null; }
  catch { return false; }
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { accessToken } = useAuth();
  const location = useLocation();
  if (!accessToken) return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  return <>{children}</>;
}

export default function App() {
  const [showSplash, setShowSplash] = useState(shouldShowSplash);
  const finishSplash = useCallback(() => {
    try { window.sessionStorage.setItem(SPLASH_KEY, "1"); } catch { /* 탭 동안만 상태로 유지 */ }
    setShowSplash(false);
  }, []);

  if (showSplash) return <SplashScreen onDone={finishSplash} />;

  return (
    <Routes>
      <Route path="/login" element={<LoginScreen />} />
      {/* 카카오 인가 리다이렉트의 착지점. 토큰이 아직 없으므로 RequireAuth 밖이다 */}
      <Route path="/login/kakao" element={<KakaoCallbackScreen />} />
      <Route
        path="/*"
        element={
          <RequireAuth>
            <Routes>
              <Route path="/" element={<HomeScreen />} />
              <Route path="/birthdate" element={<BirthdateScreen />} />
              <Route path="/media-consent" element={<MediaConsentScreen />} />
              <Route path="/match" element={<MatchWaitScreen />} />
              <Route path="/sessions/:sessionId" element={<SessionScreen />} />
              <Route path="/sessions/:sessionId/result" element={<ResultScreen />} />
              <Route path="/appeals/:evictionId" element={<AppealScreen />} />
              <Route path="/goal" element={<GoalScreen />} />
              <Route path="/points" element={<PointsScreen />} />
              <Route path="/store" element={<StoreScreen />} />
              <Route path="/records" element={<RecordsScreen />} />
              <Route path="/report" element={<ReportScreen />} />
              <Route path="/profile" element={<ProfileScreen />} />
              <Route path="/leave" element={<LeaveScreen />} />
              <Route path="/admin" element={<AdminGuard><AdminDashboardScreen /></AdminGuard>} />
              <Route path="/admin/reports" element={<AdminGuard><AdminReportsScreen /></AdminGuard>} />
              <Route path="/admin/reports/:caseId" element={<AdminGuard><AdminReportsScreen /></AdminGuard>} />
              <Route path="/admin/appeals" element={<AdminGuard><AdminAppealsScreen /></AdminGuard>} />
              <Route path="/admin/appeals/:appealId" element={<AdminGuard><AdminAppealsScreen /></AdminGuard>} />
              <Route path="/admin/sessions" element={<AdminGuard><AdminSessionsScreen /></AdminGuard>} />
              <Route path="/admin/withdrawals" element={<AdminGuard><AdminWithdrawalsScreen /></AdminGuard>} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </RequireAuth>
        }
      />
    </Routes>
  );
}
