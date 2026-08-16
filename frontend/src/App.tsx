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
import { GoalScreen, PointsScreen, RecordsScreen, ReportScreen, StoreScreen } from "./screens/stubs";

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
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </RequireAuth>
        }
      />
    </Routes>
  );
}
