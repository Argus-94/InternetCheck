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
            <Ionicons name={st.icon} size={24} color={st.bg === "#334155" ? c.sub : st.bg} style={styles.icon} />
            <Text style={[styles.time, { color: c.sub }]}>{formatTime(h.time)}</Text>
            <View style={styles.mid}>
              <Text style={[styles.title, { color: c.text }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.8}>
                {h.title}
              </Text>
              <View style={styles.speeds}>
                <Text style={[styles.down, { color: c.text }]} numberOfLines={1}>
                  ↓ {h.downText ?? h.speedText}
                </Text>
                {h.upText ? (
                  <Text style={[styles.up, { color: h.upOld || h.upText === "не измерена" ? c.sub : c.text }]} numberOfLines={1}>
                    ↑ {h.upText}
                    {h.upOld ? " прош." : ""}
                  </Text>
                ) : null}
              </View>
              {h.net || h.cause ? (
                <Text style={[styles.sub, { color: c.sub }]} numberOfLines={1}>
                  {[h.net, h.cause].filter((x) => x).join(" · ")}
                </Text>
              ) : null}
            </View>
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "flex-start", gap: 10, minHeight: 48, paddingVertical: 10 },
  icon: { marginTop: 1 },
  time: { fontSize: 15, width: 48, marginTop: 2 },
  mid: { flex: 1 },
  title: { fontSize: 16, fontWeight: "600" },
  // Две колонки одинаковой ширины: скачивание и отправка стоят ровно друг под другом во всех строках.
  speeds: { flexDirection: "row", marginTop: 2 },
  down: { fontSize: 14, fontWeight: "700", width: 104 },
  up: { fontSize: 14, fontWeight: "700", flex: 1 },
  sub: { fontSize: 13, lineHeight: 18, marginTop: 2 },
  empty: { fontSize: 16 },
});
