import { useCallback, useEffect, useState } from "react";
import { AppState } from "react-native";
import { getSnapshot, type Snapshot } from "../modules/net-check";

/** Раз в секунду читает состояние из нативной службы, пока приложение на экране. */
export function useSnapshot(intervalMs = 1000) {
  const [snap, setSnap] = useState<Snapshot | null>(() => getSnapshot());
  const refresh = useCallback(() => setSnap(getSnapshot()), []);

  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | null = null;
    const start = () => {
      if (timer) return;
      refresh();
      timer = setInterval(refresh, intervalMs);
    };
    const stop = () => {
      if (timer) clearInterval(timer);
      timer = null;
    };
    if (AppState.currentState === "active") start();
    const sub = AppState.addEventListener("change", (s) => (s === "active" ? start() : stop()));
    return () => {
      stop();
      sub.remove();
    };
  }, [intervalMs, refresh]);

  return { snap, refresh };
}
