import { Pressable, StyleSheet, Text, View } from "react-native";
import { usePalette } from "../theme";

type Props<T extends string | number> = {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
};

/** Переключатель из нескольких вариантов в одну строку (вкладки, период). */
export function Segmented<T extends string | number>({ options, value, onChange }: Props<T>) {
  const c = usePalette();
  return (
    <View style={[styles.wrap, { backgroundColor: c.card, borderColor: c.border }]} accessibilityRole="tablist">
      {options.map((o) => {
        const on = o.value === value;
        return (
          <Pressable
            key={String(o.value)}
            accessibilityRole="tab"
            accessibilityState={{ selected: on }}
            onPress={() => onChange(o.value)}
            style={[styles.item, on ? { backgroundColor: c.accent } : null]}
          >
            <Text style={[styles.text, { color: on ? c.onAccent : c.text }]} numberOfLines={1}>
              {o.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { flexDirection: "row", borderRadius: 14, borderWidth: 1, padding: 3, gap: 3 },
  item: { flex: 1, minHeight: 44, borderRadius: 11, alignItems: "center", justifyContent: "center", paddingHorizontal: 6 },
  text: { fontSize: 16, fontWeight: "700" },
});
