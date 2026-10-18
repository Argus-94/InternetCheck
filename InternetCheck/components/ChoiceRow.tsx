import { Pressable, StyleSheet, Text, View } from "react-native";
import { Ionicons } from "@expo/vector-icons";
import { usePalette } from "../theme";

type Props = { label: string; note: string; selected: boolean; onPress: () => void };

/** Один вариант из нескольких (радио-кнопка). */
export function ChoiceRow({ label, note, selected, onPress }: Props) {
  const c = usePalette();
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityLabel={label + ", " + note}
      accessibilityState={{ checked: selected }}
      onPress={onPress}
      style={({ pressed }) => [styles.row, { opacity: pressed ? 0.7 : 1 }]}
    >
      <Ionicons
        name={selected ? "radio-button-on" : "radio-button-off"}
        size={26}
        color={selected ? c.accent : c.sub}
      />
      <View style={styles.texts}>
        <Text style={[styles.label, { color: c.text }]}>{label}</Text>
        <Text style={[styles.note, { color: c.sub }]}>{note}</Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center", gap: 12, minHeight: 56, paddingVertical: 6 },
  texts: { flex: 1 },
  label: { fontSize: 17, fontWeight: "600" },
  note: { fontSize: 15, marginTop: 2 },
});
