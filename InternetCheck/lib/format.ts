const pad = (n: number) => (n < 10 ? "0" + n : String(n));

export function formatTime(ms: number): string {
  const d = new Date(ms);
  return pad(d.getHours()) + ":" + pad(d.getMinutes());
}

export function formatBytes(n: number): string {
  if (n < 1024 * 1024) return Math.round(n / 1024) + " КБ";
  if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1).replace(".", ",") + " МБ";
  return (n / (1024 * 1024 * 1024)).toFixed(2).replace(".", ",") + " ГБ";
}

export function formatCountdown(sec: number): string {
  if (sec <= 1) return "сейчас";
  if (sec < 90) return "через " + Math.round(sec) + " с";
  return "через ~" + Math.round(sec / 60) + " мин";
}

export function formatDuration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return s + " с";
  if (s < 3600) return Math.floor(s / 60) + " мин " + (s % 60) + " с";
  return Math.floor(s / 3600) + " ч " + Math.floor((s % 3600) / 60) + " мин";
}

export function formatClock(ms: number): string {
  const d = new Date(ms);
  return pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
}

export function formatSpeed(bps: number): string {
  if (bps < 0) return "—";
  if (bps >= 1_000_000) return (bps / 1_000_000).toFixed(1).replace(".", ",") + " МБ/с";
  if (bps >= 1000) return Math.round(bps / 1000) + " КБ/с";
  return bps > 0 ? "<1 КБ/с" : "0 КБ/с";
}

/** Минуты от полуночи -> «23:30». */
export function formatMinutes(m: number): string {
  return pad(Math.floor(m / 60) % 24) + ":" + pad(m % 60);
}

export function formatDayTime(ms: number): string {
  const d = new Date(ms);
  return pad(d.getDate()) + "." + pad(d.getMonth() + 1) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
}
