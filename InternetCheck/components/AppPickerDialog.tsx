import { useEffect, useMemo, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, TextInput } from "react-native";
import { getApps, type AppItem } from "../modules/net-check";
import { usePalette } from "../theme";
import { DialogShell } from "./Dialog";

type Props = {
  visible: boolean;
  current: string;
  onClose: () => void;
  onPick: (pkg: string, label: string) => void;
};

/** Выбор приложения, которое предложит открыть уведомление, когда интернет появится. */
export function AppPickerDialog({ visible, current, onClose, onPick }: Props) {
  const c = usePalette();
  const [apps, setApps] = useState<AppItem[]>([]);
  const [q, setQ] = useState("");

  useEffect(() => {
    if (visible) {
      setApps(getApps());
      setQ("");
    }
  }, [visible]);

  const shown = useMemo(() => {
    const s = q.trim().toLowerCase();
    return s ? apps.filter((a) => a.label.toLowerCase().includes(s)) : apps;
  }, [apps, q]);

  return (
    <DialogShell
      visible={visible}
      onClose={onClose}
      title="Какое приложение открыть"
      icon="apps-outline"
      buttons={[{ text: "Отмена", kind: "secondary", onPress: onClose }]}
    >
      <TextInput
        value={q}
        onChangeText={setQ}
        placeholder="Поиск"
        placeholderTextColor={c.sub}
        accessibilityLabel="Поиск приложения"
        style={[styles.input, { color: c.text, backgroundColor: c.bg, borderColor: c.border }]}
      />
      <ScrollView style={styles.list} nestedScrollEnabled keyboardShouldPersistTaps="handled">
        {shown.map((a, i) => (
          <Pressable
            key={a.pkg}
            accessibilityRole="button"
            accessibilityLabel={a.label}
            onPress={() => onPick(a.pkg, a.label)}
            style={({ pressed }) => [
              styles.row,
              i > 0 ? { borderTopColor: c.border, borderTopWidth: 1 } : null,
              { opacity: pressed ? 0.7 : 1 },
            ]}
          >
            <Text
              style={[styles.text, { color: a.pkg === current ? c.accent : c.text, fontWeight: a.pkg === current ? "800" : "500" }]}
              numberOfLines={1}
            >
              {a.label}
            </Text>
          </Pressable>
        ))}
        {shown.length === 0 ? (
          <Text style={[styles.empty, { color: c.sub }]}>
            {apps.length === 0 ? "Список приложений недоступен." : "Ничего не найдено."}
          </Text>
        ) : null}
      </ScrollView>
    </DialogShell>
  );
}

const styles = StyleSheet.create({
  input: { minHeight: 48, borderRadius: 12, borderWidth: 1, paddingHorizontal: 14, fontSize: 17 },
  list: { maxHeight: 300 },
  row: { minHeight: 48, justifyContent: "center", paddingVertical: 8 },
  text: { fontSize: 17 },
  empty: { fontSize: 15, paddingVertical: 10 },
});
