import { useEffect, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { Ionicons } from "@expo/vector-icons";
import { getSounds, NetCheck, type SoundItem } from "../modules/net-check";
import { usePalette } from "../theme";
import { DialogShell } from "./Dialog";

type Props = {
  visible: boolean;
  currentUri: string;
  currentTitle: string;
  loud: boolean;
  onClose: () => void;
  onPick: (uri: string, title: string) => void;
};

const TABS = [
  { type: 2, label: "Уведомления" },
  { type: 4, label: "Будильники" },
  { type: 1, label: "Звонки" },
];

const DEFAULT_ITEM: SoundItem = { uri: "", title: "Системный по умолчанию" };
const SILENT_ITEM: SoundItem = { uri: "silent", title: "Без звука" };

/** Выбор звука оповещения из системных звуков с прослушиванием по нажатию. */
export function SoundDialog({ visible, currentUri, currentTitle, loud, onClose, onPick }: Props) {
  const c = usePalette();
  const [tab, setTab] = useState(0);
  const [items, setItems] = useState<SoundItem[]>([]);
  const [sel, setSel] = useState<SoundItem>(DEFAULT_ITEM);

  useEffect(() => {
    if (visible) {
      setSel({ uri: currentUri, title: currentTitle || (currentUri === "" ? DEFAULT_ITEM.title : "Выбранный звук") });
      setTab(0);
    } else {
      NetCheck.stopSound();
    }
  }, [visible, currentUri, currentTitle]);

  useEffect(() => {
    if (visible) setItems(getSounds(TABS[tab].type));
  }, [visible, tab]);

  function choose(it: SoundItem) {
    setSel(it);
    if (it.uri === "silent") NetCheck.stopSound();
    else NetCheck.playSound(it.uri, loud);
  }

  function close() {
    NetCheck.stopSound();
    onClose();
  }

  const all = [DEFAULT_ITEM, SILENT_ITEM, ...items];

  return (
    <DialogShell
      visible={visible}
      onClose={close}
      title="Звук оповещения"
      icon="musical-notes-outline"
      buttons={[
        {
          text: "Выбрать",
          kind: "primary",
          onPress: () => {
            NetCheck.stopSound();
            onPick(sel.uri, sel.uri === "" ? "" : sel.title);
          },
        },
        { text: "Системные настройки звука", kind: "secondary", onPress: () => NetCheck.openChannelSettings() },
        { text: "Отмена", kind: "secondary", onPress: close },
      ]}
    >
      <View style={[styles.tabs, { backgroundColor: c.bg, borderColor: c.border }]}>
        {TABS.map((t, i) => (
          <Pressable
            key={t.type}
            accessibilityRole="tab"
            accessibilityState={{ selected: tab === i }}
            onPress={() => setTab(i)}
            style={[styles.tab, tab === i ? { backgroundColor: c.accent } : null]}
          >
            <Text style={[styles.tabText, { color: tab === i ? c.onAccent : c.text }]} numberOfLines={1}>
              {t.label}
            </Text>
          </Pressable>
        ))}
      </View>
      <ScrollView style={styles.list} nestedScrollEnabled>
        {all.map((it, i) => {
          const on = sel.uri === it.uri;
          return (
            <Pressable
              key={it.uri + "-" + i}
              accessibilityRole="radio"
              accessibilityState={{ checked: on }}
              accessibilityLabel={it.title}
              onPress={() => choose(it)}
              style={({ pressed }) => [
                styles.row,
                i > 0 ? { borderTopColor: c.border, borderTopWidth: 1 } : null,
                { opacity: pressed ? 0.7 : 1 },
              ]}
            >
              <Ionicons name={on ? "radio-button-on" : "radio-button-off"} size={24} color={on ? c.accent : c.sub} />
              <Text style={[styles.rowText, { color: c.text }]} numberOfLines={2}>
                {it.title}
              </Text>
              {on && it.uri !== "silent" ? <Ionicons name="volume-high-outline" size={20} color={c.sub} /> : null}
            </Pressable>
          );
        })}
        {items.length === 0 ? (
          <Text style={[styles.empty, { color: c.sub }]}>
            Системные звуки этого раздела не найдены. Звук можно выбрать в настройках канала уведомлений.
          </Text>
        ) : null}
      </ScrollView>
    </DialogShell>
  );
}

const styles = StyleSheet.create({
  tabs: { flexDirection: "row", borderRadius: 12, borderWidth: 1, padding: 3, gap: 3 },
  tab: { flex: 1, minHeight: 40, borderRadius: 9, alignItems: "center", justifyContent: "center", paddingHorizontal: 4 },
  tabText: { fontSize: 14, fontWeight: "700" },
  list: { maxHeight: 300 },
  row: { flexDirection: "row", alignItems: "center", gap: 12, minHeight: 52, paddingVertical: 8 },
  rowText: { flex: 1, fontSize: 17 },
  empty: { fontSize: 15, lineHeight: 21, paddingVertical: 10 },
});
