import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { Modal, Pressable, StyleSheet, Text, View } from "react-native";
import { Ionicons } from "@expo/vector-icons";
import { usePalette, type IconName } from "../theme";

export type DialogButton = {
  text: string;
  kind?: "primary" | "secondary" | "danger";
  onPress?: () => void;
};

export type DialogSpec = {
  title: string;
  message?: string;
  icon?: IconName;
  tone?: "default" | "danger";
  buttons?: DialogButton[];
};

type ShellProps = {
  visible: boolean;
  onClose: () => void;
  title: string;
  icon?: IconName;
  tone?: "default" | "danger";
  buttons: DialogButton[];
  children?: ReactNode;
};

/** Единая оболочка всех диалогов: затемнение, карточка в цветах темы, крупные кнопки. */
export function DialogShell({ visible, onClose, title, icon, tone = "default", buttons, children }: ShellProps) {
  const c = usePalette();
  const accent = tone === "danger" ? (c.dark ? "#FCA5A5" : "#B91C1C") : c.accent;
  return (
    <Modal transparent visible={visible} animationType="fade" statusBarTranslucent onRequestClose={onClose}>
      <View style={styles.root}>
        <Pressable
          style={[StyleSheet.absoluteFill, { backgroundColor: c.dark ? "rgba(0,0,0,0.7)" : "rgba(15,23,42,0.5)" }]}
          onPress={onClose}
          accessibilityLabel="Закрыть"
        />
        <View
          accessibilityViewIsModal
          style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}
        >
          <View style={styles.head}>
            {icon ? (
              <View style={[styles.iconWrap, { backgroundColor: c.dark ? "#1F2D42" : "#EAF2FB" }]}>
                <Ionicons name={icon} size={26} color={accent} />
              </View>
            ) : null}
            <Text accessibilityRole="header" style={[styles.title, { color: c.text }]}>
              {title}
            </Text>
          </View>
          {children}
          <View style={styles.buttons}>
            {buttons.map((b, i) => (
              <DialogBtn key={b.text + i} b={b} />
            ))}
          </View>
        </View>
      </View>
    </Modal>
  );
}

function DialogBtn({ b }: { b: DialogButton }) {
  const c = usePalette();
  const kind = b.kind ?? "secondary";
  const bg = kind === "primary" ? c.accent : kind === "danger" ? (c.dark ? "#7F1D1D" : "#B91C1C") : c.bg;
  const fg = kind === "primary" ? c.onAccent : kind === "danger" ? "#FFFFFF" : c.text;
  const border = kind === "primary" ? c.accent : kind === "danger" ? bg : c.border;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={b.text}
      onPress={b.onPress}
      style={({ pressed }) => [
        styles.btn,
        { backgroundColor: bg, borderColor: border, opacity: pressed ? 0.85 : 1 },
      ]}
    >
      <Text style={[styles.btnText, { color: fg }]}>{b.text}</Text>
    </Pressable>
  );
}

type Ctx = { show: (s: DialogSpec) => void; hide: () => void };
const DialogContext = createContext<Ctx>({ show: () => {}, hide: () => {} });

export function useDialog(): Ctx {
  return useContext(DialogContext);
}

/** Простые диалоги (подтверждения и сообщения) в общем стиле вместо системного Alert. */
export function DialogProvider({ children }: { children: ReactNode }) {
  const c = usePalette();
  const [spec, setSpec] = useState<DialogSpec | null>(null);
  const hide = useCallback(() => setSpec(null), []);
  const show = useCallback((s: DialogSpec) => setSpec(s), []);
  const value = useMemo(() => ({ show, hide }), [show, hide]);

  const buttons: DialogButton[] = (spec?.buttons ?? [{ text: "Понятно", kind: "primary" }]).map((b) => ({
    ...b,
    onPress: () => {
      setSpec(null);
      b.onPress?.();
    },
  }));

  return (
    <DialogContext.Provider value={value}>
      {children}
      <DialogShell
        visible={spec !== null}
        onClose={hide}
        title={spec?.title ?? ""}
        icon={spec?.icon}
        tone={spec?.tone}
        buttons={buttons}
      >
        {spec?.message ? <Text style={[styles.message, { color: c.sub }]}>{spec.message}</Text> : null}
      </DialogShell>
    </DialogContext.Provider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, justifyContent: "center", alignItems: "center", padding: 20 },
  card: {
    width: "100%",
    maxWidth: 440,
    borderRadius: 24,
    borderWidth: 1,
    padding: 20,
    gap: 14,
    elevation: 12,
    shadowColor: "#000",
    shadowOpacity: 0.3,
    shadowRadius: 16,
    shadowOffset: { width: 0, height: 6 },
  },
  head: { flexDirection: "row", alignItems: "center", gap: 12 },
  iconWrap: { width: 44, height: 44, borderRadius: 22, alignItems: "center", justifyContent: "center" },
  title: { flex: 1, fontSize: 21, fontWeight: "800" },
  message: { fontSize: 17, lineHeight: 24 },
  buttons: { gap: 10, marginTop: 4 },
  btn: {
    minHeight: 52,
    borderRadius: 14,
    borderWidth: 2,
    paddingHorizontal: 16,
    paddingVertical: 10,
    alignItems: "center",
    justifyContent: "center",
  },
  btnText: { fontSize: 17, fontWeight: "700", textAlign: "center" },
});
