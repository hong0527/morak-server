import { Navigate, Route, Routes, useLocation } from "react-router-dom";

import { useAuth } from "./auth/useAuth";
import LoginScreen from "./screens/LoginScreen";
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

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { accessToken } = useAuth();
  const location = useLocation();
  if (!accessToken) return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginScreen />} />
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
