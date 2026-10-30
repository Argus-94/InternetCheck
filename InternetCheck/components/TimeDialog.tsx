import { useEffect, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { formatMinutes } from "../lib/format";
import { usePalette } from "../theme";
import { DialogShell } from "./Dialog";

type Props = {
  visible: boolean;
  title: string;
  value: number; // минуты от полуночи
  onClose: () => void;
  onPick: (minutes: number) => void;
};

const STEPS = [
  { label: "−1 ч", d: -60 },
  { label: "−15 мин", d: -15 },
  { label: "+15 мин", d: 15 },
  { label: "+1 ч", d: 60 },
];

/** Выбор времени без внешних библиотек: крупные часы и кнопки шагами. */
export function TimeDialog({ visible, title, value, onClose, onPick }: Props) {
  const c = usePalette();
  const [v, setV] = useState(value);

  useEffect(() => {
    if (visible) setV(value);
  }, [visible, value]);

  const shift = (d: number) => setV((x) => (((x + d) % 1440) + 1440) % 1440);

  return (
    <DialogShell
      visible={visible}
      onClose={onClose}
      title={title}
      icon="time-outline"
      buttons={[
        { text: "Готово", kind: "primary", onPress: () => onPick(v) },
        { text: "Отмена", kind: "secondary", onPress: onClose },
      ]}
    >
      <Text accessibilityLiveRegion="polite" style={[styles.time, { color: c.text }]}>
        {formatMinutes(v)}
      </Text>
      <View style={styles.row}>
        {STEPS.map((s) => (
          <Pressable
            key={s.label}
            accessibilityRole="button"
            accessibilityLabel={s.label}
            onPress={() => shift(s.d)}
            style={({ pressed }) => [
              styles.step,
              { backgroundColor: c.bg, borderColor: c.border, opacity: pressed ? 0.7 : 1 },
            ]}
          >
            <Text style={[styles.stepText, { color: c.text }]}>{s.label}</Text>
          </Pressable>
        ))}
      </View>
    </DialogShell>
  );
}

const styles = StyleSheet.create({
  time: { fontSize: 52, fontWeight: "800", textAlign: "center" },
  row: { flexDirection: "row", gap: 8 },
  step: { flex: 1, minHeight: 48, borderRadius: 12, borderWidth: 1, alignItems: "center", justifyContent: "center" },
  stepText: { fontSize: 14, fontWeight: "700" },
});
