import { Pressable, StyleSheet, Switch, Text, View } from "react-native";
import { usePalette } from "../theme";

type Props = { label: string; note?: string; value: boolean; onChange: (v: boolean) => void };

export function SwitchRow({ label, note, value, onChange }: Props) {
  const c = usePalette();
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityState={{ checked: value }}
      onPress={() => onChange(!value)}
      style={styles.row}
    >
      <View style={styles.texts}>
        <Text style={[styles.label, { color: c.text }]}>{label}</Text>
        {note ? <Text style={[styles.note, { color: c.sub }]}>{note}</Text> : null}
      </View>
      <Switch
        value={value}
        onValueChange={onChange}
        accessible={false}
        trackColor={{ false: c.border, true: c.accent }}
        thumbColor="#FFFFFF"
      />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center", gap: 12, minHeight: 56, paddingVertical: 6 },
  texts: { flex: 1 },
  label: { fontSize: 17, fontWeight: "600" },
  note: { fontSize: 15, marginTop: 2 },
});
