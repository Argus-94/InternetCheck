import { StyleSheet, Text, View } from "react-native";
import { Ionicons } from "@expo/vector-icons";
import { levelStyle, usePalette } from "../theme";
import { formatTime } from "../lib/format";
import type { HistoryItem } from "../modules/net-check";

/** Последние проверки: время, итог и скорость (значок + текст, не только цвет). */
export function HistoryList({ items }: { items: HistoryItem[] }) {
  const c = usePalette();
  if (items.length === 0) {
    return <Text style={[styles.empty, { color: c.sub }]}>Проверок пока не было.</Text>;
  }
  const shown = items.slice(-10).reverse();
  return (
    <View>
      {shown.map((h, i) => {
        const st = levelStyle(h.level, true);
        return (
          <View
            key={h.time + "-" + i}
            style={[styles.row, i > 0 ? { borderTopColor: c.border, borderTopWidth: 1 } : null]}
            accessible
            accessibilityLabel={formatTime(h.time) + ", " + h.title + ", " + h.speedText + (h.net ? ", " + h.net : "") + (h.cause ? ", " + h.cause : "")}
          >
            <Ionicons name={st.icon} size={24} color={st.bg === "#334155" ? c.sub : st.bg} />
            <Text style={[styles.time, { color: c.sub }]}>{formatTime(h.time)}</Text>
            <View style={styles.mid}>
              <Text style={[styles.title, { color: c.text }]} numberOfLines={2}>
                {h.title}
              </Text>
              {h.net || h.cause ? (
                <Text style={[styles.sub, { color: c.sub }]} numberOfLines={3}>
                  {[h.net, h.cause].filter((x) => x).join(" · ")}
                </Text>
              ) : null}
            </View>
            <Text style={[styles.speed, { color: c.text }]}>{h.speedText}</Text>
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center", gap: 10, minHeight: 48, paddingVertical: 8 },
  time: { fontSize: 15, width: 48 },
  mid: { flex: 1 },
  title: { fontSize: 16 },
  sub: { fontSize: 13, lineHeight: 18, marginTop: 2 },
  speed: { fontSize: 15, fontWeight: "700" },
  empty: { fontSize: 16 },
});
