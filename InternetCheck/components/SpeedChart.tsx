import { useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { levelStyle, usePalette } from "../theme";
import { formatDayTime, formatSpeed } from "../lib/format";
import type { ChartData } from "../modules/net-check";

const H = 130;

/** Столбики скорости: слева скачивание (цвет по качеству), справа выгрузка. Без внешних библиотек. */
export function SpeedChart({ data }: { data: ChartData }) {
  const c = usePalette();
  const [w, setW] = useState(0);
  const items = data.items;
  const max = Math.max(1, ...items.map((i) => Math.max(i.d, i.u)));
  const has = items.some((i) => i.l > 0);
  const end = data.from + data.step * items.length;

  const summary = has
    ? "График скорости. Максимум " + formatSpeed(max) + "."
    : "График скорости. Данных пока нет.";

  return (
    <View accessible accessibilityLabel={summary}>
      <View style={styles.top}>
        <Text style={[styles.label, { color: c.sub }]}>{formatSpeed(max)}</Text>
        <View style={styles.legend}>
          <View style={[styles.dot, { backgroundColor: "#1E7B34" }]} />
          <Text style={[styles.label, { color: c.sub }]}>↓</Text>
          <View style={[styles.dot, { backgroundColor: c.accent }]} />
          <Text style={[styles.label, { color: c.sub }]}>↑</Text>
        </View>
      </View>
      <View
        style={[styles.plot, { borderColor: c.border, backgroundColor: c.bg }]}
        onLayout={(e) => setW(e.nativeEvent.layout.width)}
      >
        {w > 0
          ? items.map((it, i) => {
              const colW = w / items.length;
              const left = i * colW;
              if (it.l === 0) {
                return (
                  <View
                    key={i}
                    style={[styles.empty, { left: left + colW / 2 - 1, backgroundColor: c.border }]}
                  />
                );
              }
              const dh = Math.max(2, (it.d / max) * (H - 6));
              const uh = it.u >= 0 ? Math.max(2, (it.u / max) * (H - 6)) : 0;
              const bw = Math.max(1, colW / 2 - 1);
              return (
                <View key={i} style={[styles.col, { left, width: colW }]}>
                  <View style={[styles.bar, { height: dh, width: bw, backgroundColor: levelStyle(it.l, true).bg }]} />
                  {uh > 0 ? (
                    <View style={[styles.bar, { height: uh, width: bw, backgroundColor: c.accent, opacity: 0.85 }]} />
                  ) : null}
                </View>
              );
            })
          : null}
      </View>
      <View style={styles.axis}>
        <Text style={[styles.label, { color: c.sub }]}>{formatDayTime(data.from)}</Text>
        <Text style={[styles.label, { color: c.sub }]}>{formatDayTime(end)}</Text>
      </View>
      {!has ? (
        <Text style={[styles.note, { color: c.sub }]}>
          Данные появятся после первых проверок. Серые точки — периоды без проверок.
        </Text>
      ) : (
        <Text style={[styles.note, { color: c.sub }]}>
          Цвет столбика — худшее качество за период: зелёный нормальный, жёлто-зелёный средний, оранжевый слабый,
          красный — данных нет.
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  top: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", marginBottom: 6 },
  legend: { flexDirection: "row", alignItems: "center", gap: 6 },
  dot: { width: 10, height: 10, borderRadius: 5 },
  plot: { height: H, borderWidth: 1, borderRadius: 10, overflow: "hidden" },
  col: { position: "absolute", bottom: 3, flexDirection: "row", alignItems: "flex-end", justifyContent: "center", gap: 1 },
  bar: { borderTopLeftRadius: 2, borderTopRightRadius: 2 },
  empty: { position: "absolute", bottom: 4, width: 2, height: 2, borderRadius: 1 },
  axis: { flexDirection: "row", justifyContent: "space-between", marginTop: 6 },
  label: { fontSize: 13 },
  note: { fontSize: 14, lineHeight: 20, marginTop: 8 },
});
