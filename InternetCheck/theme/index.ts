import type { ComponentProps } from "react";
import { useColorScheme } from "react-native";
import type { Ionicons } from "@expo/vector-icons";

export type IconName = ComponentProps<typeof Ionicons>["name"];

export const LEVEL = {
  UNKNOWN: 0,
  OFFLINE: 1,
  NONE: 2,
  WEAK: 3,
  MEDIUM: 4,
  GOOD: 5,
} as const;

/** Цвета подобраны так, чтобы белый текст на них читался (контраст ≥ 4.5:1). */
export function levelStyle(level: number, monitoring: boolean): { bg: string; icon: IconName } {
  switch (level) {
    case LEVEL.GOOD:
      return { bg: "#1E7B34", icon: "checkmark-circle" };
    case LEVEL.MEDIUM:
      return { bg: "#4D7C0F", icon: "checkmark-circle-outline" };
    case LEVEL.WEAK:
      return { bg: "#B45309", icon: "warning" };
    case LEVEL.NONE:
      return { bg: "#B91C1C", icon: "close-circle" };
    case LEVEL.OFFLINE:
      return { bg: "#4B5563", icon: "cloud-offline" };
    default:
      return { bg: "#334155", icon: monitoring ? "time" : "pause-circle" };
  }
}

export type Palette = {
  dark: boolean;
  bg: string;
  card: string;
  text: string;
  sub: string;
  border: string;
  accent: string;
  onAccent: string;
  danger: string;
};

export function usePalette(): Palette {
  const dark = useColorScheme() === "dark";
  return dark
    ? {
        dark,
        bg: "#0B1220",
        card: "#16202F",
        text: "#F1F5F9",
        sub: "#B6C2D2",
        border: "#2A3A52",
        accent: "#5BB5FF",
        onAccent: "#06223D",
        danger: "#FCA5A5",
      }
    : {
        dark,
        bg: "#F3F6FA",
        card: "#FFFFFF",
        text: "#0F172A",
        sub: "#475569",
        border: "#D5DEE9",
        accent: "#0B63C5",
        onAccent: "#FFFFFF",
        danger: "#B91C1C",
      };
}
