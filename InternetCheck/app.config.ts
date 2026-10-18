import type { ExpoConfig, ConfigContext } from "expo/config";
import withFixGradleProperties from "./plugins/withFixGradleProperties";

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: "Интернет есть?",
  slug: "internet-check",
  scheme: "internetcheck",
  version: "1.0.8",
  orientation: "portrait",
  userInterfaceStyle: "automatic",
  newArchEnabled: true,
  icon: "./assets/images/icon.png",
  android: {
    package: "com.example.internetcheck",
    versionCode: 8,
    adaptiveIcon: {
      foregroundImage: "./assets/images/adaptive-icon-fg.png",
      backgroundColor: "#12324A"
    },
    permissions: []
  },
  plugins: [
    "expo-router",
    withFixGradleProperties
  ],
  extra: {}
});
