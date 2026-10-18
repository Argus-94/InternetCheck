import { useEffect, useState } from "react";
import {
  AppState,
  Linking,
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { AppPickerDialog } from "../components/AppPickerDialog";
import { BigButton } from "../components/BigButton";
import { ChoiceRow } from "../components/ChoiceRow";
import { useDialog } from "../components/Dialog";
import { HistoryList } from "../components/HistoryList";
import { InputDialog } from "../components/InputDialog";
import { LinkRow } from "../components/LinkRow";
import { Section } from "../components/Section";
import { Segmented } from "../components/Segmented";
import { SoundDialog } from "../components/SoundDialog";
import { StatsView } from "../components/StatsView";
import { StatusCard } from "../components/StatusCard";
import { SwitchRow } from "../components/SwitchRow";
import { ThresholdDialog } from "../components/ThresholdDialog";
import { TimeDialog } from "../components/TimeDialog";
import { useSnapshot } from "../hooks/useSnapshot";
import { formatBytes, formatMinutes } from "../lib/format";
import { getSnapshot, isAvailable, NetCheck } from "../modules/net-check";
import { usePalette } from "../theme";

const INTERVALS = [
  { sec: 120, label: "Часто", note: "каждые 2 минуты · до 23 МБ в час" },
  { sec: 300, label: "Обычно", note: "каждые 5 минут · до 9 МБ в час" },
  { sec: 900, label: "Редко", note: "каждые 15 минут · около 3 МБ в час" },
];

const REPEATS = [
  { sec: 30, label: "Каждые 30 секунд" },
  { sec: 60, label: "Каждую минуту" },
  { sec: 120, label: "Каждые 2 минуты" },
];

const TABS = [
  { value: "status", label: "Статус" },
  { value: "stats", label: "Статистика" },
  { value: "settings", label: "Настройки" },
] as const;
type Tab = (typeof TABS)[number]["value"];

export default function Index() {
  const c = usePalette();
  const dlg = useDialog();
  const { snap, refresh } = useSnapshot(500);
  const [now, setNow] = useState(Date.now());
  const [tab, setTab] = useState<Tab>("status");
  const [soundOpen, setSoundOpen] = useState(false);
  const [timeEdit, setTimeEdit] = useState<"from" | "to" | null>(null);
  const [thresholdOpen, setThresholdOpen] = useState(false);
  const [appOpen, setAppOpen] = useState(false);
  const [urlOpen, setUrlOpen] = useState(false);

  useEffect(() => {
    setNow(Date.now());
  }, [snap]);

  // Если слежение должно работать, а служба остановлена системой — запускаем снова.
  useEffect(() => {
    const s = getSnapshot();
    if (s && s.monitoring && !s.serviceRunning) {
      NetCheck.start();
      refresh();
    }
  }, [refresh]);

  // Открыли приложение — считаем оповещение прочитанным: повторы сигнала прекращаются.
  useEffect(() => {
    NetCheck.ackAlert();
    const sub = AppState.addEventListener("change", (s) => {
      if (s === "active") NetCheck.ackAlert();
    });
    return () => sub.remove();
  }, []);

  /** Разрешение на уведомления (Android 13+). Возвращает true, если можно показывать уведомления. */
  async function ensureNotificationPermission(): Promise<boolean> {
    if (Platform.OS !== "android" || typeof Platform.Version !== "number" || Platform.Version < 33) return true;
    const perm = PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS;
    if (await PermissionsAndroid.check(perm)) return true;
    const res = await PermissionsAndroid.request(perm);
    if (res === PermissionsAndroid.RESULTS.GRANTED) return true;
    dlg.show({
      title: "Нужно разрешение на уведомления",
      message:
        "Без него состояние интернета и оповещения не появятся. Разрешите уведомления для этого приложения в настройках телефона.",
      icon: "notifications-off-outline",
      buttons: [
        { text: "Открыть настройки", kind: "primary", onPress: () => Linking.openSettings() },
        { text: "Отмена", kind: "secondary" },
      ],
    });
    return false;
  }

  async function toggleMonitoring() {
    if (!snap) return;
    if (snap.monitoring) {
      NetCheck.stop();
      refresh();
      return;
    }
    if (!(await ensureNotificationPermission())) return;
    if (!NetCheck.start()) {
      dlg.show({
        title: "Не удалось запустить",
        message: "Система не разрешила запуск службы. Попробуйте ещё раз.",
        icon: "alert-circle-outline",
        tone: "danger",
      });
    }
    refresh();
  }

  function checkNow() {
    NetCheck.checkNow();
    refresh();
  }

  async function testAlert() {
    if (!(await ensureNotificationPermission())) return;
    NetCheck.testAlert();
  }

  function confirmResetTraffic() {
    dlg.show({
      title: "Сбросить счётчик?",
      message: "Счётчик потраченного на проверки трафика начнётся с нуля.",
      icon: "help-circle-outline",
      buttons: [
        {
          text: "Сбросить",
          kind: "primary",
          onPress: () => {
            NetCheck.resetTraffic();
            refresh();
          },
        },
        { text: "Отмена", kind: "secondary" },
      ],
    });
  }

  function confirmClearHistory() {
    dlg.show({
      title: "Очистить историю?",
      message: "Список последних проверок будет удалён. Текущий статус останется.",
      icon: "trash-outline",
      tone: "danger",
      buttons: [
        {
          text: "Очистить",
          kind: "danger",
          onPress: () => {
            NetCheck.clearHistory();
            refresh();
          },
        },
        { text: "Отмена", kind: "secondary" },
      ],
    });
  }

  if (!isAvailable || !snap) {
    return (
      <SafeAreaView style={[styles.safe, { backgroundColor: c.bg }]}>
        <View style={styles.center}>
          <Text style={[styles.h1, { color: c.text }]}>Интернет есть?</Text>
          <Text style={[styles.body, { color: c.sub }]}>
            Приложение работает только в собранной Android-версии (не в Expo Go и не в браузере).
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  const soundNote =
    snap.soundUri === "silent"
      ? "Без звука"
      : snap.soundUri === ""
        ? "Системный по умолчанию"
        : snap.soundTitle || "Выбранный звук";

  const setTarget = (mode: number, down = snap.targetDownKb, up = snap.targetUpKb, wifi = snap.onlyWifi) => {
    NetCheck.setTarget(mode, down, up, wifi);
    refresh();
  };
  const setQuiet = (on: boolean, from = snap.quietFrom, to = snap.quietTo) => {
    NetCheck.setQuiet(on, from, to);
    refresh();
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: c.bg }]}>
      <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
        <Text accessibilityRole="header" style={[styles.h1, { color: c.text }]}>
          Интернет есть?
        </Text>

        <Segmented options={[...TABS]} value={tab} onChange={setTab} />

        {tab === "status" ? (
          <View>
            <View style={{ height: 16 }} />
            <StatusCard snap={snap} now={now} />

            <View style={styles.buttons}>
              <BigButton
                label={snap.monitoring ? "Остановить слежение" : "Включить слежение"}
                icon={snap.monitoring ? "stop-circle-outline" : "play-circle-outline"}
                variant={snap.monitoring ? "danger" : "primary"}
                onPress={toggleMonitoring}
              />
              <BigButton
                label={snap.checking ? "Проверяю…" : "Проверить сейчас"}
                icon="refresh"
                variant="secondary"
                busy={snap.checking}
                disabled={snap.checking}
                onPress={checkNow}
              />
            </View>

            {snap.monitoring && !snap.ignoringBattery ? (
              <Section title="Чтобы работало при погашенном экране">
                <Text style={[styles.body, { color: c.text }]}>
                  Без этого разрешения телефон может усыплять фоновые приложения, и оповещение придёт с задержкой.
                  Разрешите приложению работать без ограничений батареи.
                </Text>
                <View style={{ height: 12 }} />
                <BigButton
                  label="Разрешить"
                  icon="battery-charging-outline"
                  onPress={() => NetCheck.openBatterySettings()}
                />
              </Section>
            ) : null}

            <Section
              title="Последние проверки"
              right={
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="Очистить историю"
                  disabled={snap.history.length === 0}
                  onPress={confirmClearHistory}
                  style={({ pressed }) => [
                    styles.clearBtn,
                    { borderColor: c.border, opacity: snap.history.length === 0 ? 0.4 : pressed ? 0.7 : 1 },
                  ]}
                >
                  <Text style={[styles.clearText, { color: c.danger }]}>Очистить</Text>
                </Pressable>
              }
            >
              <HistoryList items={snap.history} />
            </Section>
          </View>
        ) : null}

        {tab === "stats" ? (
          <View style={{ marginTop: 16 }}>
            <StatsView />
          </View>
        ) : null}

        {tab === "settings" ? (
          <View>
            <Section title="Что считать нормальным интернетом">
              <ChoiceRow
                label="Средний и выше"
                note="скачивание от 100 КБ/с, выгрузка от 50 КБ/с"
                selected={snap.targetMode === 0}
                onPress={() => setTarget(0)}
              />
              <ChoiceRow
                label="Только хороший"
                note="скачивание от 400 КБ/с, выгрузка от 150 КБ/с"
                selected={snap.targetMode === 1}
                onPress={() => setTarget(1)}
              />
              <ChoiceRow
                label="Свой порог"
                note={"скачивание от " + snap.targetDownKb + " КБ/с, выгрузка от " + snap.targetUpKb + " КБ/с"}
                selected={snap.targetMode === 2}
                onPress={() => {
                  setTarget(2);
                  setThresholdOpen(true);
                }}
              />
              <SwitchRow
                label="Только Wi‑Fi"
                note="Мобильный интернет не считается нормальным, даже если быстрый"
                value={snap.onlyWifi}
                onChange={(v) => setTarget(snap.targetMode, snap.targetDownKb, snap.targetUpKb, v)}
              />
            </Section>

            <Section title="Оповещения">
              <SwitchRow
                label="Когда интернет появился"
                note="Отдельное уведомление со звуком, как только условие выполнено"
                value={snap.alerts}
                onChange={(v) => {
                  NetCheck.setAlerts(v);
                  refresh();
                }}
              />
              <SwitchRow
                label="Когда интернет пропал"
                note="Уведомление после второй проверки подряд, с причиной"
                value={snap.alertsLost}
                onChange={(v) => {
                  NetCheck.setAlertsLost(v);
                  refresh();
                }}
              />
              <LinkRow label="Звук оповещения" note={soundNote} onPress={() => setSoundOpen(true)} />
              <SwitchRow
                label="Вибрация"
                value={snap.vibrate}
                onChange={(v) => {
                  NetCheck.setVibrate(v);
                  refresh();
                }}
              />
              <SwitchRow
                label="Громкость как у будильника"
                note="Сигнал слышно, даже если громкость уведомлений убавлена"
                value={snap.loud}
                onChange={(v) => {
                  NetCheck.setLoud(v);
                  refresh();
                }}
              />
              <SwitchRow
                label="Повторять сигнал, пока не подтвержу"
                note="До 20 повторов. Остановить: кнопка «Принял» или открыть приложение"
                value={snap.repeat}
                onChange={(v) => {
                  NetCheck.setRepeat(v, snap.repeatSec);
                  refresh();
                }}
              />
              {snap.repeat
                ? REPEATS.map((o) => (
                    <ChoiceRow
                      key={o.sec}
                      label={o.label}
                      note=""
                      selected={snap.repeatSec === o.sec}
                      onPress={() => {
                        NetCheck.setRepeat(true, o.sec);
                        refresh();
                      }}
                    />
                  ))
                : null}
              <SwitchRow
                label="Быстрое ожидание"
                note="Пока интернета нет, проверка каждые 5 с (около 1 КБ) и процессор не засыпает. Оповещение приходит за несколько секунд, расход батареи выше."
                value={snap.fastWatch}
                onChange={(v) => {
                  NetCheck.setFastWatch(v);
                  refresh();
                }}
              />
              <View style={{ height: 8 }} />
              <BigButton label="Проверить оповещение" icon="notifications-outline" variant="secondary" onPress={testAlert} />
            </Section>

            <Section title="Тихие часы">
              <SwitchRow
                label="Без звука по расписанию"
                note={
                  snap.quietOn && snap.quietNow
                    ? "Сейчас тихие часы: оповещения приходят без звука и вибрации"
                    : "В это время оповещения приходят тихо, повторы сигнала отключены"
                }
                value={snap.quietOn}
                onChange={(v) => setQuiet(v)}
              />
              {snap.quietOn ? (
                <>
                  <LinkRow label="С" note={formatMinutes(snap.quietFrom)} onPress={() => setTimeEdit("from")} />
                  <LinkRow label="До" note={formatMinutes(snap.quietTo)} onPress={() => setTimeEdit("to")} />
                </>
              ) : null}
            </Section>

            <Section title="Когда интернет появился">
              <ChoiceRow
                label="Ничего больше"
                note="только оповещение"
                selected={snap.actionKind === ""}
                onPress={() => {
                  NetCheck.setAction("", "", "");
                  refresh();
                }}
              />
              <ChoiceRow
                label="Кнопка «Открыть приложение»"
                note={snap.actionKind === "app" ? snap.actionLabel || snap.actionTarget : "выбрать приложение"}
                selected={snap.actionKind === "app"}
                onPress={() => setAppOpen(true)}
              />
              <ChoiceRow
                label="Кнопка «Открыть ссылку»"
                note={snap.actionKind === "url" ? snap.actionTarget : "указать адрес"}
                selected={snap.actionKind === "url"}
                onPress={() => setUrlOpen(true)}
              />
              <Text style={[styles.note, { color: c.sub }]}>
                Начиная с Android 10 система запрещает открывать приложения из фона, поэтому действие появляется
                кнопкой в оповещении: одно нажатие — и нужное приложение открыто.
              </Text>
            </Section>

            <Section title="Как часто проверять скорость">
              {INTERVALS.map((o) => (
                <ChoiceRow
                  key={o.sec}
                  label={o.label}
                  note={o.note}
                  selected={snap.intervalSec === o.sec}
                  onPress={() => {
                    NetCheck.setInterval(o.sec);
                    refresh();
                  }}
                />
              ))}
              <Text style={[styles.note, { color: c.sub }]}>
                Интервал относится к полным проверкам. Когда интернета нет, работает быстрое ожидание.
              </Text>
            </Section>

            <Section title="Запуск и быстрый доступ">
              <SwitchRow
                label="Запускать после включения телефона"
                note="Слежение само включится, если оно было включено"
                value={snap.autostart}
                onChange={(v) => {
                  NetCheck.setAutostart(v);
                  refresh();
                }}
              />
              <Text style={[styles.note, { color: c.sub }]}>
                Виджет «Интернет есть?» добавляется с рабочего стола (долгое нажатие → Виджеты). Плитка — в быстрых
                настройках: потяните шторку, нажмите карандаш и перетащите плитку. Нажатие на плитку включает слежение
                или запускает проверку.
              </Text>
            </Section>

            <Section title="Как это работает">
              <Text style={[styles.body, { color: c.text }]}>
                Приложение скачивает до 250 КБ с нескольких серверов (отвечает первый) и отправляет 512 КБ, считая
                скорость в обе стороны. Итог — по худшему направлению.
              </Text>
              <View style={{ height: 8 }} />
              <Text style={[styles.body, { color: c.text }]}>
                Скачивание: средний от 100 КБ/с, хороший от 400 КБ/с. Выгрузка: средний от 50 КБ/с, хороший от
                150 КБ/с. Ниже 10 КБ/с интернета нет. Если интернета нет, приложение определяет причину: нет сети,
                страница входа Wi‑Fi, не работает DNS, нет выхода в интернет, ограничение скорости.
              </Text>
              <View style={{ height: 12 }} />
              <Text style={[styles.body, { color: c.text }]}>
                Потрачено на проверки: {formatBytes(snap.trafficBytes)}
              </Text>
              <View style={{ height: 8 }} />
              <BigButton label="Сбросить счётчик" variant="secondary" onPress={confirmResetTraffic} />
            </Section>
          </View>
        ) : null}
      </ScrollView>

      <SoundDialog
        visible={soundOpen}
        currentUri={snap.soundUri}
        currentTitle={snap.soundTitle}
        loud={snap.loud}
        onClose={() => setSoundOpen(false)}
        onPick={(uri, title) => {
          NetCheck.setAlertSound(uri, title);
          setSoundOpen(false);
          refresh();
        }}
      />
      <TimeDialog
        visible={timeEdit !== null}
        title={timeEdit === "from" ? "Тихие часы начинаются" : "Тихие часы заканчиваются"}
        value={timeEdit === "to" ? snap.quietTo : snap.quietFrom}
        onClose={() => setTimeEdit(null)}
        onPick={(m) => {
          if (timeEdit === "from") setQuiet(true, m, snap.quietTo);
          else setQuiet(true, snap.quietFrom, m);
          setTimeEdit(null);
        }}
      />
      <ThresholdDialog
        visible={thresholdOpen}
        downKb={snap.targetDownKb}
        upKb={snap.targetUpKb}
        onClose={() => setThresholdOpen(false)}
        onPick={(d, u) => {
          setTarget(2, d, u);
          setThresholdOpen(false);
        }}
      />
      <AppPickerDialog
        visible={appOpen}
        current={snap.actionKind === "app" ? snap.actionTarget : ""}
        onClose={() => setAppOpen(false)}
        onPick={(pkg, label) => {
          NetCheck.setAction("app", pkg, label);
          setAppOpen(false);
          refresh();
        }}
      />
      <InputDialog
        visible={urlOpen}
        title="Ссылка для кнопки"
        icon="link-outline"
        message="Адрес откроется в браузере или в приложении, которое его обрабатывает."
        placeholder="https://example.com"
        initial={snap.actionKind === "url" ? snap.actionTarget : "https://"}
        keyboard="url"
        validate={(v) => (/^https?:\/\/\S+\.\S+/i.test(v) ? null : "Введите адрес вида https://example.com")}
        onClose={() => setUrlOpen(false)}
        onSubmit={(v) => {
          NetCheck.setAction("url", v, v.replace(/^https?:\/\//i, ""));
          setUrlOpen(false);
          refresh();
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { padding: 20, paddingBottom: 48 },
  center: { flex: 1, padding: 24, justifyContent: "center", gap: 12 },
  h1: { fontSize: 30, fontWeight: "800", marginBottom: 16 },
  body: { fontSize: 17, lineHeight: 24 },
  note: { fontSize: 15, lineHeight: 21, marginTop: 8 },
  buttons: { gap: 12, marginTop: 16 },
  clearBtn: {
    minHeight: 40,
    paddingHorizontal: 16,
    borderRadius: 12,
    borderWidth: 1,
    alignItems: "center",
    justifyContent: "center",
  },
  clearText: { fontSize: 16, fontWeight: "700" },
});
