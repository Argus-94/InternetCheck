import type { ExpoConfig, ConfigContext } from "expo/config";
import withReleaseSigning from "./plugins/withReleaseSigning";
import withFixGradleProperties from "./plugins/withFixGradleProperties";

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: "Интернет есть?",
  slug: "internet-check",
  scheme: "internetcheck",
  version: "1.0.18",
  orientation: "portrait",
  userInterfaceStyle: "automatic",
  newArchEnabled: true,
  icon: "./assets/images/icon.png",
  android: {
    package: "com.example.internetcheck",
    versionCode: 18,
    adaptiveIcon: {
      foregroundImage: "./assets/images/adaptive-icon-fg.png",
      backgroundColor: "#12324A"
    },
    blockedPermissions: ["android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.RECORD_AUDIO"],
    permissions: []
  },
  plugins: [
    "expo-router",
    withFixGradleProperties as any, // Expo также поддерживает inline config plugins
    withReleaseSigning as any
  ],
  extra: {}
});
