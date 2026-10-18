import { ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { Ionicons } from "@expo/vector-icons";
import { LEVEL, levelStyle } from "../theme";
import { formatClock, formatCountdown, formatDuration, formatTime } from "../lib/format";
import type { Snapshot } from "../modules/net-check";

const PHASE = ["", "Подключение…", "Скачивание…", "Отправка…", "Выясняю причину…"];

/** Большая карточка: итог последней проверки или ход текущей в реальном времени. */
export function StatusCard({ snap, now }: { snap: Snapshot; now: number }) {
  const last = snap.last;
  const live = snap.live;
  const level = last ? last.level : LEVEL.UNKNOWN;
  const st = levelStyle(level, snap.monitoring);
  const measuring = snap.checking && live.phase > 0;

  let title: string;
  let hint: string;
  if (last) {
    title = last.title;
    hint = last.hint;
  } else if (snap.monitoring) {
    title = "Проверяю интернет…";
    hint = "Идёт первая проверка.";
  } else {
    title = "Слежение выключено";
    hint = "Включите слежение — состояние интернета появится в шторке уведомлений.";
  }

  const showSpeed = last !== null && level >= LEVEL.NONE;
  const details: string[] = [];
  if (last) {
    details.push("Проверено в " + formatTime(last.time));
    if (level >= LEVEL.WEAK) details.push("отклик " + last.latencyMs + " мс");
    if (last.transport !== "none") details.push(last.net && last.net !== last.transportLabel ? last.net + " (" + last.transportLabel + ")" : last.transportLabel);
  }
  const next =
    snap.monitoring && snap.nextCheckAt > 0 && !snap.live.quickMode
      ? "Следующая проверка " + formatCountdown((snap.nextCheckAt - now) / 1000)
      : null;

  const since =
    snap.monitoring && snap.stateSince > 0 && snap.stable !== null
      ? (snap.stable ? "Нормальный интернет: уже " : "Нормального интернета нет: уже ") +
        formatDuration(now - snap.stateSince)
      : null;

  const waiting = snap.monitoring && live.quickMode && !measuring;

  const a11y = measuring
    ? [PHASE[live.phase], live.downText ? "скачивание " + live.downText : "", live.upText ? "выгрузка " + live.upText : ""]
        .filter((s) => s)
        .join(". ")
    : [
        title,
        showSpeed && last ? "скачивание " + last.speedText : "",
        last && last.upText ? "выгрузка " + last.upText : "",
        last ? last.upNote : "",
        hint,
        last ? last.cause : "",
        last ? last.loadNote : "",
        since ?? "",
      ]
        .filter((s) => s)
        .join(". ");

  return (
    <View
      style={[styles.card, { backgroundColor: st.bg }]}
      accessible
      accessibilityRole="summary"
      accessibilityLabel={a11y}
      accessibilityLiveRegion="polite"
    >
      <View style={styles.top}>
        <Ionicons name={st.icon} size={64} color="#FFFFFF" />
        {snap.checking ? <ActivityIndicator color="#FFFFFF" size="small" /> : null}
      </View>
      <Text style={styles.title}>{title}</Text>

      {measuring ? (
        <View style={styles.liveBox}>
          <Text style={styles.phase}>{PHASE[live.phase] || "Проверка…"}</Text>
          <View style={styles.track}>
            <View style={[styles.fill, { width: `${Math.max(3, Math.min(100, live.progress))}%` }]} />
          </View>
          <Text style={styles.up}>↓ {live.downText || "…"}</Text>
          <Text style={styles.up}>↑ {live.upText || (live.phase === 3 ? "…" : "—")}</Text>
          {live.latencyMs >= 0 ? <Text style={styles.small}>Отклик {live.latencyMs} мс</Text> : null}
        </View>
      ) : (
        <>
          {showSpeed && last ? <Text style={styles.speed}>↓ {last.speedText}</Text> : null}
          {showSpeed && last && last.upText ? <Text style={styles.up}>↑ {last.upText} (выгрузка)</Text> : null}
          {last && last.upNote ? <Text style={styles.warn}>{last.upNote}</Text> : null}
        </>
      )}

      <Text style={styles.hint}>{hint}</Text>
      {!measuring && last && last.cause ? <Text style={styles.strong}>{last.cause}</Text> : null}
      {!measuring && last && last.loadNote ? <Text style={styles.small}>{last.loadNote}</Text> : null}
      {since ? <Text style={styles.strong}>{since}</Text> : null}
      {waiting ? (
        <Text style={styles.small}>
          Жду интернет: проверка каждые 5 с · попыток {live.quickCount}
          {live.quickAt > 0 ? " · последняя в " + formatClock(live.quickAt) : ""}
        </Text>
      ) : null}
      {details.length > 0 ? <Text style={styles.small}>{details.join(" · ")}</Text> : null}
      {next ? <Text style={styles.small}>{next}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: 24, padding: 22, gap: 6 },
  top: { flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  title: { color: "#FFFFFF", fontSize: 28, fontWeight: "800", marginTop: 6 },
  speed: { color: "#FFFFFF", fontSize: 44, fontWeight: "800" },
  up: { color: "#FFFFFF", fontSize: 24, fontWeight: "700" },
  warn: { color: "#FFFFFF", fontSize: 17, fontWeight: "700", marginTop: 4 },
  hint: { color: "#FFFFFF", fontSize: 18, lineHeight: 25 },
  strong: { color: "#FFFFFF", fontSize: 17, fontWeight: "700", marginTop: 2 },
  small: { color: "#FFFFFF", fontSize: 15, opacity: 0.95, marginTop: 2 },
  liveBox: { gap: 6, marginVertical: 4 },
  phase: { color: "#FFFFFF", fontSize: 20, fontWeight: "700" },
  track: { height: 10, borderRadius: 5, backgroundColor: "rgba(255,255,255,0.3)", overflow: "hidden" },
  fill: { height: 10, borderRadius: 5, backgroundColor: "#FFFFFF" },
});
