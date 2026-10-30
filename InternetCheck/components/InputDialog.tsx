import { useEffect, useState } from "react";
import { StyleSheet, Text, TextInput } from "react-native";
import type { IconName } from "../theme";
import { usePalette } from "../theme";
import { DialogShell } from "./Dialog";

type Props = {
  visible: boolean;
  title: string;
  icon?: IconName;
  message?: string;
  placeholder?: string;
  initial: string;
  keyboard?: "default" | "url" | "numeric";
  confirmText?: string;
  /** Вернуть текст ошибки, если значение не подходит. */
  validate?: (v: string) => string | null;
  onClose: () => void;
  onSubmit: (v: string) => void;
};

/** Диалог с одним полем ввода в общем стиле. */
export function InputDialog({
  visible,
  title,
  icon,
  message,
  placeholder,
  initial,
  keyboard = "default",
  confirmText = "Сохранить",
  validate,
  onClose,
  onSubmit,
}: Props) {
  const c = usePalette();
  const [v, setV] = useState(initial);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (visible) {
      setV(initial);
      setErr(null);
    }
  }, [visible, initial]);

  function submit() {
    const e = validate ? validate(v.trim()) : null;
    if (e) {
      setErr(e);
      return;
    }
    onSubmit(v.trim());
  }

  return (
    <DialogShell
      visible={visible}
      onClose={onClose}
      title={title}
      icon={icon}
      buttons={[
        { text: confirmText, kind: "primary", onPress: submit },
        { text: "Отмена", kind: "secondary", onPress: onClose },
      ]}
    >
      {message ? <Text style={[styles.msg, { color: c.sub }]}>{message}</Text> : null}
      <TextInput
        value={v}
        onChangeText={(t) => {
          setV(t);
          setErr(null);
        }}
        placeholder={placeholder}
        placeholderTextColor={c.sub}
        keyboardType={keyboard === "url" ? "url" : keyboard === "numeric" ? "number-pad" : "default"}
        autoCapitalize="none"
        autoCorrect={false}
        accessibilityLabel={title}
        style={[styles.input, { color: c.text, backgroundColor: c.bg, borderColor: err ? c.danger : c.border }]}
      />
      {err ? <Text style={[styles.err, { color: c.danger }]}>{err}</Text> : null}
    </DialogShell>
  );
}

const styles = StyleSheet.create({
  msg: { fontSize: 16, lineHeight: 22 },
  input: { minHeight: 52, borderRadius: 12, borderWidth: 2, paddingHorizontal: 14, fontSize: 18 },
  err: { fontSize: 15, fontWeight: "600" },
});
