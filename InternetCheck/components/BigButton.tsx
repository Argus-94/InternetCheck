import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { Ionicons } from "@expo/vector-icons";
import { usePalette, type IconName } from "../theme";

type Props = {
  label: string;
  onPress: () => void;
  variant?: "primary" | "secondary" | "danger";
  icon?: IconName;
  busy?: boolean;
  disabled?: boolean;
};

export function BigButton({ label, onPress, variant = "primary", icon, busy, disabled }: Props) {
  const c = usePalette();
  const bg = variant === "primary" ? c.accent : variant === "danger" ? c.card : c.card;
  const fg = variant === "primary" ? c.onAccent : variant === "danger" ? c.danger : c.accent;
  const border = variant === "primary" ? c.accent : variant === "danger" ? c.danger : c.accent;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: !!disabled, busy: !!busy }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.btn,
        { backgroundColor: bg, borderColor: border, opacity: disabled ? 0.5 : pressed ? 0.85 : 1 },
      ]}
    >
      <View style={styles.row}>
        {busy ? (
          <ActivityIndicator color={fg} />
        ) : icon ? (
          <Ionicons name={icon} size={22} color={fg} />
        ) : null}
        <Text style={[styles.text, { color: fg }]}>{label}</Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  btn: {
    minHeight: 56,
    borderRadius: 16,
    borderWidth: 2,
    paddingHorizontal: 20,
    paddingVertical: 12,
    justifyContent: "center",
  },
  row: { flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 10 },
  text: { fontSize: 18, fontWeight: "700", textAlign: "center", flexShrink: 1 },
});
