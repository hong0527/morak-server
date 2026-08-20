import { useEffect, useState } from "react";
import { Navigate } from "react-router-dom";

import { api } from "../api/client";
import type { MemberMe } from "../api/types";

export default function AdminGuard({ children }: { children: React.ReactNode }) {
  const [me, setMe] = useState<MemberMe | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void api.member.me()
      .then((data) => !cancelled && setMe(data))
      .catch(() => !cancelled && setFailed(true));
    return () => { cancelled = true; };
  }, []);

  if (failed) return <Navigate to="/" replace />;
  if (!me) return <div className="screen"><p className="muted">관리자 권한을 확인하고 있습니다.</p></div>;
  if (me.role !== "ADMIN") return <Navigate to="/" replace />;
  return <>{children}</>;
}
