import type { ReactNode } from "react";
import { StyleSheet, Text, View } from "react-native";
import { usePalette } from "../theme";

type Props = { title: string; children: ReactNode; right?: ReactNode };

export function Section({ title, children, right }: Props) {
  const c = usePalette();
  return (
    <View style={styles.wrap}>
      <View style={styles.head}>
        <Text accessibilityRole="header" style={[styles.title, { color: c.text }]}>
          {title}
        </Text>
        {right}
      </View>
      <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}>{children}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { marginTop: 24 },
  head: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", marginBottom: 10, gap: 12 },
  title: { fontSize: 20, fontWeight: "700", flexShrink: 1 },
  card: { borderRadius: 16, borderWidth: 1, padding: 16, gap: 4 },
});
