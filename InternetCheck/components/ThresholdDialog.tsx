import { useEffect, useState } from "react";
import { StyleSheet, Text, TextInput, View } from "react-native";
import { usePalette } from "../theme";
import { DialogShell } from "./Dialog";

type Props = {
  visible: boolean;
  downKb: number;
  upKb: number;
  onClose: () => void;
  onPick: (downKb: number, upKb: number) => void;
};

/** Свой порог «нормального» интернета: скачивание и выгрузка в КБ/с. */
export function ThresholdDialog({ visible, downKb, upKb, onClose, onPick }: Props) {
  const c = usePalette();
  const [d, setD] = useState(String(downKb));
  const [u, setU] = useState(String(upKb));
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (visible) {
      setD(String(downKb));
      setU(String(upKb));
      setErr(null);
    }
  }, [visible, downKb, upKb]);

  function save() {
    const dn = parseInt(d, 10);
    const up = parseInt(u, 10);
    if (!Number.isFinite(dn) || dn < 10 || dn > 100000) {
      setErr("Скачивание: число от 10 до 100000 КБ/с");
      return;
    }
    if (!Number.isFinite(up) || up < 0 || up > 100000) {
      setErr("Выгрузка: число от 0 до 100000 КБ/с");
      return;
    }
    onPick(dn, up);
  }

  const field = (label: string, v: string, set: (s: string) => void) => (
    <View style={styles.field}>
      <Text style={[styles.label, { color: c.text }]}>{label}</Text>
      <TextInput
        value={v}
        onChangeText={(t) => {
          set(t.replace(/[^0-9]/g, ""));
          setErr(null);
        }}
        keyboardType="number-pad"
        accessibilityLabel={label}
        style={[styles.input, { color: c.text, backgroundColor: c.bg, borderColor: c.border }]}
      />
    </View>
  );

  return (
    <DialogShell
      visible={visible}
      onClose={onClose}
      title="Свой порог"
      icon="speedometer-outline"
      buttons={[
        { text: "Сохранить", kind: "primary", onPress: save },
        { text: "Отмена", kind: "secondary", onPress: onClose },
      ]}
    >
      <Text style={[styles.msg, { color: c.sub }]}>
        Интернет считается нормальным, когда скорость не ниже этих значений. Для выгрузки 0 — не проверять.
      </Text>
      {field("Скачивание, КБ/с", d, setD)}
      {field("Выгрузка, КБ/с", u, setU)}
      {err ? <Text style={[styles.err, { color: c.danger }]}>{err}</Text> : null}
    </DialogShell>
  );
}

const styles = StyleSheet.create({
  msg: { fontSize: 16, lineHeight: 22 },
  field: { gap: 6 },
  label: { fontSize: 16, fontWeight: "600" },
  input: { minHeight: 52, borderRadius: 12, borderWidth: 2, paddingHorizontal: 14, fontSize: 20, fontWeight: "700" },
  err: { fontSize: 15, fontWeight: "600" },
});
