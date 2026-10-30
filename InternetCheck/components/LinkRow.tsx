import { Pressable, StyleSheet, Text, View } from "react-native";
import { Ionicons } from "@expo/vector-icons";
import { usePalette } from "../theme";

type Props = { label: string; note?: string; onPress: () => void };

/** Строка настроек, которая открывает выбор (диалог). */
export function LinkRow({ label, note, onPress }: Props) {
  const c = usePalette();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label + (note ? ", " + note : "")}
      onPress={onPress}
      style={({ pressed }) => [styles.row, { opacity: pressed ? 0.7 : 1 }]}
    >
      <View style={styles.texts}>
        <Text style={[styles.label, { color: c.text }]}>{label}</Text>
        {note ? <Text style={[styles.note, { color: c.sub }]}>{note}</Text> : null}
      </View>
      <Ionicons name="chevron-forward" size={22} color={c.sub} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center", gap: 12, minHeight: 56, paddingVertical: 6 },
  texts: { flex: 1 },
  label: { fontSize: 17, fontWeight: "600" },
  note: { fontSize: 15, marginTop: 2 },
});
