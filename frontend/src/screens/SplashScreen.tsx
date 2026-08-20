import { useEffect, useState } from "react";

const SPLASH_MS = 2000;
const EXIT_MS = 260;

export default function SplashScreen({ onDone }: { onDone: () => void }) {
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    const exitId = window.setTimeout(() => setLeaving(true), SPLASH_MS - EXIT_MS);
    const doneId = window.setTimeout(onDone, SPLASH_MS);
    return () => { window.clearTimeout(exitId); window.clearTimeout(doneId); };
  }, [onDone]);

  return (
    <main className={`splash${leaving ? " leaving" : ""}`} aria-label="모락 시작 화면">
      <div className="splash-visual" aria-hidden="true">
        {Array.from({ length: 6 }, (_, index) => (
          <span key={index}><i></i><b></b></span>
        ))}
        <div className="splash-spark">M</div>
      </div>
      <div className="splash-brand">
        <h1>모락</h1>
        <p>함께 시작하는 목표 달성</p>
      </div>
      <div className="splash-progress"><span></span></div>
    </main>
  );
}
