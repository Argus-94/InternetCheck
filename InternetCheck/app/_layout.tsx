import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import { DialogProvider } from "../components/Dialog";

export default function RootLayout() {
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <DialogProvider>
        <StatusBar style="auto" />
        <Stack screenOptions={{ headerShown: false }} />
      </DialogProvider>
    </GestureHandlerRootView>
  );
}
