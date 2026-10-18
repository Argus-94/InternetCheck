import { useCallback, useEffect, useState } from "react";
import { PermissionsAndroid, Platform, Share, StyleSheet, Text, View } from "react-native";
import { BigButton } from "./BigButton";
import { useDialog } from "./Dialog";
import { LinkRow } from "./LinkRow";
import { Section } from "./Section";
import { Segmented } from "./Segmented";
import { SpeedChart } from "./SpeedChart";
import { formatDuration, formatSpeed } from "../lib/format";
import {
  exportReport,
  getChart,
  getNetworks,
  getStats,
  NetCheck,
  type ChartData,
  type NetRow,
  type Stats,
} from "../modules/net-check";
import { usePalette } from "../theme";

const PERIODS = [
  { value: 24, label: "24 часа" },
  { value: 168, label: "7 дней" },
];

/** Вкладка «Статистика»: график скорости, итоги периода, сравнение сетей, отправка отчёта. */
export function StatsView() {
  const c = usePalette();
  const dlg = useDialog();
  const [hours, setHours] = useState(24);
  const [chart, setChart] = useState<ChartData>({ from: 0, step: 1, items: [] });
  const [stats, setStats] = useState<Stats | null>(null);
  const [nets, setNets] = useState<NetRow[]>([]);

  const load = useCallback(() => {
    setChart(getChart(hours));
    setStats(getStats(hours));
    setNets(getNetworks(hours));
  }, [hours]);

  useEffect(() => {
    load();
    const t = setInterval(load, 15000);
    return () => clearInterval(t);
  }, [load]);

  async function askWifiNames() {
    if (Platform.OS !== "android") return;
    dlg.show({
      title: "Названия Wi‑Fi сетей",
      message:
        "Чтобы записывать, в какой Wi‑Fi сети были проверки, Android требует разрешение на местоположение. Местоположение не определяется и не сохраняется — читается только название сети. Без разрешения сети подписываются просто «Wi‑Fi».",
      icon: "wifi-outline",
      buttons: [
        {
          text: "Разрешить",
          kind: "primary",
          onPress: () => {
            PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION);
          },
        },
        { text: "Не сейчас", kind: "secondary" },
      ],
    });
  }

  function share() {
    const text = exportReport(hours);
    if (!text) {
      dlg.show({ title: "Нет данных", message: "Отчёт появится после первых проверок.", icon: "alert-circle-outline" });
      return;
    }
    Share.share({ message: text, title: "Интернет есть? — отчёт" }).catch(() => {});
  }

  function confirmClear() {
    dlg.show({
      title: "Очистить статистику?",
      message: "График, итоги и сравнение сетей начнутся с нуля. Список последних проверок на главной не меняется.",
      icon: "trash-outline",
      tone: "danger",
      buttons: [
        {
          text: "Очистить",
          kind: "danger",
          onPress: () => {
            NetCheck.clearStats();
            load();
          },
        },
        { text: "Отмена", kind: "secondary" },
      ],
    });
  }

  const row = (label: string, value: string) => (
    <View style={styles.row} accessible accessibilityLabel={label + ": " + value}>
      <Text style={[styles.label, { color: c.sub }]}>{label}</Text>
      <Text style={[styles.value, { color: c.text }]}>{value}</Text>
    </View>
  );

  const avail =
    stats && stats.availability !== null ? stats.availability.toFixed(1).replace(".", ",") + "%" : "пока нет данных";

  return (
    <View>
      <Segmented options={PERIODS} value={hours} onChange={setHours} />

      <Section title="График скорости">
        <SpeedChart data={chart} />
      </Section>

      <Section title="Итоги">
        {row("Нормальный интернет", avail + (stats && stats.availability !== null ? " времени" : ""))}
        {row("Пропаданий", stats ? String(stats.outages) : "—")}
        {row("Самый долгий перерыв", stats && stats.longestMs > 0 ? formatDuration(stats.longestMs) : "нет")}
        {row("Средняя скорость ↓", stats ? formatSpeed(stats.avgDown) : "—")}
        {row("Средняя скорость ↑", stats ? formatSpeed(stats.avgUp) : "—")}
        {row("Проверок", stats ? String(stats.checks) : "—")}
      </Section>

      <Section title="По сетям">
        {nets.length === 0 ? (
          <Text style={[styles.empty, { color: c.sub }]}>Проверок за этот период ещё не было.</Text>
        ) : (
          nets.map((n, i) => (
            <View
              key={n.name + n.transport}
              style={[styles.net, i > 0 ? { borderTopColor: c.border, borderTopWidth: 1 } : null]}
              accessible
              accessibilityLabel={
                n.name + ", проверок " + n.checks + ", скачивание " + formatSpeed(n.avgDown) + ", нормальный " + Math.round(n.goodPct) + " процентов"
              }
            >
              <Text style={[styles.netName, { color: c.text }]} numberOfLines={1}>
                {n.name}
              </Text>
              <Text style={[styles.netInfo, { color: c.sub }]}>
                ↓ {formatSpeed(n.avgDown)} · ↑ {formatSpeed(n.avgUp)} · нормальный в {Math.round(n.goodPct)}% проверок ·
                всего {n.checks}
              </Text>
            </View>
          ))
        )}
        <LinkRow
          label="Названия Wi‑Fi сетей"
          note="Нужно разрешение на местоположение — иначе сети подписываются просто «Wi‑Fi»"
          onPress={askWifiNames}
        />
      </Section>

      <Section title="Отчёт">
        <Text style={[styles.empty, { color: c.sub }]}>
          Текст с итогами, сменами состояния и таблицей проверок за выбранный период. Можно отправить оператору или
          провайдеру.
        </Text>
        <View style={{ height: 8 }} />
        <BigButton label="Поделиться отчётом" icon="share-outline" onPress={share} />
        <View style={{ height: 8 }} />
        <BigButton label="Очистить статистику" icon="trash-outline" variant="danger" onPress={confirmClear} />
      </Section>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", minHeight: 40, gap: 12 },
  label: { fontSize: 16, flexShrink: 1 },
  value: { fontSize: 17, fontWeight: "700", textAlign: "right", flexShrink: 1 },
  empty: { fontSize: 16, lineHeight: 22 },
  net: { paddingVertical: 10, gap: 2 },
  netName: { fontSize: 17, fontWeight: "700" },
  netInfo: { fontSize: 14, lineHeight: 20 },
});
