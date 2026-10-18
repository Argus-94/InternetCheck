import { requireNativeModule } from "expo";

export type LastResult = {
  level: number; // 0 неизвестно, 1 нет сети, 2 нет интернета, 3 слабый, 4 средний, 5 нормальный
  title: string;
  hint: string;
  speedBps: number;
  speedText: string;
  upText: string; // скорость выгрузки, пусто если не измерялась
  upNote: string; // пояснение, если выгрузка хуже скачивания
  loadNote: string; // если во время проверки канал использовали другие приложения
  upFailed: boolean; // отправку пытались измерить, но не вышло (это не «0 КБ/с»)
  callsText: string; // пригодность для звонков и игр (дрожание, потери), пусто если не мерили
  callsLevel: number; // 0 нет данных, 1 плохо, 2 терпимо, 3 хорошо
  latencyMs: number;
  transport: string;
  transportLabel: string;
  time: number;
  host: string;
  error: string;
  net: string; // название сети: Wi‑Fi или оператор
  cause: string; // причина, если интернета нет (пусто — не определялась)
};

export type HistoryItem = {
  level: number;
  title: string;
  speedText: string;
  time: number;
  net: string;
  cause: string;
};

export type ChartItem = { l: number; d: number; u: number }; // l: 0 — проверок не было
export type ChartData = { from: number; step: number; items: ChartItem[] };
export type Stats = {
  availability: number | null; // % времени с нормальным интернетом
  outages: number;
  longestMs: number;
  avgDown: number;
  avgUp: number;
  checks: number;
};
export type NetRow = {
  name: string;
  transport: string;
  checks: number;
  avgDown: number;
  avgUp: number;
  goodPct: number;
};
export type AppItem = { label: string; pkg: string };

export type LiveState = {
  phase: number; // 0 нет проверки, 1 подключение, 2 скачивание, 3 отправка
  downText: string;
  upText: string;
  latencyMs: number;
  progress: number; // 0..100
  quickMode: boolean; // режим ожидания интернета
  quickCount: number;
  quickAt: number;
};

export type SoundItem = { title: string; uri: string };

export type Snapshot = {
  monitoring: boolean;
  serviceRunning: boolean;
  checking: boolean;
  intervalSec: number;
  alerts: boolean;
  alertsLost: boolean;
  repeat: boolean;
  repeatSec: number;
  quietOn: boolean;
  quietFrom: number; // минуты от полуночи
  quietTo: number;
  quietNow: boolean;
  targetMode: number; // 0 средний и выше, 1 только хороший, 2 свой порог
  targetDownKb: number;
  targetUpKb: number;
  onlyWifi: boolean;
  upCounts: boolean; // учитывать отправку в итоге
  saveMobile: boolean; // экономия мобильного трафика
  mobileLimitMb: number; // месячный лимит трафика проверок на мобильной сети, 0 — без лимита
  mobileUsedBytes: number; // потрачено в этом месяце
  saveBattery: boolean; // реже проверять при низком заряде
  lowBattery: boolean; // заряд низкий и телефон не заряжается
  actionKind: string; // "", "app", "url"
  actionTarget: string;
  actionLabel: string;
  vibrate: boolean;
  loud: boolean;
  fastWatch: boolean;
  soundUri: string; // "" — системный по умолчанию, "silent" — без звука
  soundTitle: string;
  stateSince: number;
  stable: boolean | null;
  live: LiveState;
  autostart: boolean;
  ignoringBattery: boolean;
  trafficBytes: number;
  nextCheckAt: number;
  sdk: number;
  last: LastResult | null;
  history: HistoryItem[];
};

type Native = {
  getSnapshot(): string;
  startMonitoring(): boolean;
  stopMonitoring(): boolean;
  checkNow(): boolean;
  setCheckInterval(sec: number): boolean;
  setAlertsEnabled(on: boolean): boolean;
  setAlertsLostEnabled(on: boolean): boolean;
  setRepeat(on: boolean, sec: number): boolean;
  setQuiet(on: boolean, from: number, to: number): boolean;
  setTarget(mode: number, downKb: number, upKb: number, onlyWifi: boolean): boolean;
  setUpCounts(on: boolean): boolean;
  setSaving(saveMobile: boolean, limitMb: number, saveBattery: boolean): boolean;
  runSelfTest(): string;
  setAction(kind: string, target: string, label: string): boolean;
  getApps(): string;
  ackAlert(): boolean;
  getChart(hours: number): string;
  getStats(hours: number): string;
  getNetworks(hours: number): string;
  exportReport(hours: number): string;
  clearStats(): boolean;
  setAlertSound(uri: string, title: string): boolean;
  setVibrate(on: boolean): boolean;
  setLoud(on: boolean): boolean;
  setFastWatch(on: boolean): boolean;
  clearHistory(): boolean;
  testAlert(): boolean;
  getSounds(type: number): string;
  playSound(uri: string, loud: boolean): boolean;
  stopSound(): boolean;
  openChannelSettings(): boolean;
  setAutostart(on: boolean): boolean;
  resetTraffic(): boolean;
  requestIgnoreBatteryOptimizations(): boolean;
};

let native: Native | null = null;
try {
  native = requireNativeModule<Native>("NetCheck");
} catch {
  native = null; // не Android-сборка (например, веб или Expo Go)
}

export const isAvailable = native !== null;

export function getSnapshot(): Snapshot | null {
  if (!native) return null;
  try {
    return JSON.parse(native.getSnapshot()) as Snapshot;
  } catch {
    return null;
  }
}

function call(fn: (n: Native) => boolean): boolean {
  if (!native) return false;
  try {
    return fn(native);
  } catch {
    return false;
  }
}

/** Системные звуки: 1 — рингтоны, 2 — уведомления, 4 — будильники. */
export function getSounds(type: number): SoundItem[] {
  if (!native) return [];
  try {
    return JSON.parse(native.getSounds(type)) as SoundItem[];
  } catch {
    return [];
  }
}

function parse<T>(fn: (n: Native) => string, fallback: T): T {
  if (!native) return fallback;
  try {
    return JSON.parse(fn(native)) as T;
  } catch {
    return fallback;
  }
}

export type SelfTestResult = { total: number; failed: number; failures: string[] };

/** Самопроверка логики приложения (пороги, тексты, расчёты): работает без сети. */
export function runSelfTest(): SelfTestResult | null {
  if (!native) return null;
  try {
    return JSON.parse(native.runSelfTest()) as SelfTestResult;
  } catch {
    return null;
  }
}

export const getChart = (hours: number) =>
  parse<ChartData>((n) => n.getChart(hours), { from: 0, step: 1, items: [] });
export const getStats = (hours: number) =>
  parse<Stats | null>((n) => n.getStats(hours), null);
export const getNetworks = (hours: number) => parse<NetRow[]>((n) => n.getNetworks(hours), []);
export const getApps = () => parse<AppItem[]>((n) => n.getApps(), []);

export function exportReport(hours: number): string {
  if (!native) return "";
  try {
    return native.exportReport(hours);
  } catch {
    return "";
  }
}

export const NetCheck = {
  start: () => call((n) => n.startMonitoring()),
  stop: () => call((n) => n.stopMonitoring()),
  checkNow: () => call((n) => n.checkNow()),
  setInterval: (sec: number) => call((n) => n.setCheckInterval(sec)),
  setAlerts: (on: boolean) => call((n) => n.setAlertsEnabled(on)),
  setAlertsLost: (on: boolean) => call((n) => n.setAlertsLostEnabled(on)),
  setRepeat: (on: boolean, sec: number) => call((n) => n.setRepeat(on, sec)),
  setQuiet: (on: boolean, from: number, to: number) => call((n) => n.setQuiet(on, from, to)),
  setTarget: (mode: number, downKb: number, upKb: number, onlyWifi: boolean) =>
    call((n) => n.setTarget(mode, downKb, upKb, onlyWifi)),
  setUpCounts: (on: boolean) => call((n) => n.setUpCounts(on)),
  setSaving: (saveMobile: boolean, limitMb: number, saveBattery: boolean) =>
    call((n) => n.setSaving(saveMobile, limitMb, saveBattery)),
  setAction: (kind: string, target: string, label: string) => call((n) => n.setAction(kind, target, label)),
  ackAlert: () => call((n) => n.ackAlert()),
  clearStats: () => call((n) => n.clearStats()),
  setAlertSound: (uri: string, title: string) => call((n) => n.setAlertSound(uri, title)),
  setVibrate: (on: boolean) => call((n) => n.setVibrate(on)),
  setLoud: (on: boolean) => call((n) => n.setLoud(on)),
  setFastWatch: (on: boolean) => call((n) => n.setFastWatch(on)),
  clearHistory: () => call((n) => n.clearHistory()),
  testAlert: () => call((n) => n.testAlert()),
  playSound: (uri: string, loud: boolean) => call((n) => n.playSound(uri, loud)),
  stopSound: () => call((n) => n.stopSound()),
  openChannelSettings: () => call((n) => n.openChannelSettings()),
  setAutostart: (on: boolean) => call((n) => n.setAutostart(on)),
  resetTraffic: () => call((n) => n.resetTraffic()),
  openBatterySettings: () => call((n) => n.requestIgnoreBatteryOptimizations()),
};
