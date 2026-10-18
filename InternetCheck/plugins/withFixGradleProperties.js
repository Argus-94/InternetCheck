let configPlugins;
try {
  configPlugins = require("expo/config-plugins");
} catch (e) {
  configPlugins = require("@expo/config-plugins");
}
const { withGradleProperties, withDangerousMod } = configPlugins;
const fs = require("fs");
const path = require("path");

const REQUIRED_PROPERTIES = {
  "org.gradle.daemon": "false",
  "org.gradle.jvmargs": "-Xmx4096m -XX:MaxMetaspaceSize=1024m",
  "android.useAndroidX": "true",
  "android.enableJetifier": "false"
};

function withFixGradleProperties(config) {
  // 1. Штатно проставляем свойства через модель
  config = withGradleProperties(config, (config) => {
    for (const [key, value] of Object.entries(REQUIRED_PROPERTIES)) {
      const existingIndex = config.modResults.findIndex(
        (item) => item.type === "property" && item.key === key
      );
      if (existingIndex >= 0) {
        config.modResults[existingIndex].value = value;
      } else {
        config.modResults.push({ type: "property", key, value });
      }
    }
    return config;
  });

  // 2. withDangerousMod — правим сырой текст gradle.properties ПОСЛЕ записи на диск
  config = withDangerousMod(config, [
    "android",
    async (config) => {
      const propsPath = path.join(config.modRequest.platformProjectRoot, "gradle.properties");
      if (!fs.existsSync(propsPath)) return config;

      let text = fs.readFileSync(propsPath, "utf8");

      // 2a. Чиним склеенные строки вида "26org.gradle.daemon=false"
      text = text.replace(/^(\d+)([a-zA-Z][\w.]*=.*)$/gm, "$2");

      // 2b. Чиним склейки вида "org.gradle.daemon=falseorg.gradle.caching=true"
      // Разрезаем только ПОСЛЕ значения (после "="), не трогаем ключи и -Dorg.gradle... в jvmargs
      const glueRe =
        /^([A-Za-z][\w.-]*=[^\n]*?[^\s.])(?<!-D)(?=(?:org\.gradle\.|android\.|kotlin\.|expo\.)[A-Za-z][\w.-]*=)/m;
      for (let guard = 0; guard < 100 && glueRe.test(text); guard++) {
        text = text.replace(glueRe, "$1\n");
      }

      // 2c. Дедупликация — оставляем последнее значение каждого ключа
      const lines = text.split("\n");
      const lastIndexByKey = new Map();
      lines.forEach((line, idx) => {
        const m = line.match(/^([A-Za-z][\w.-]*)=/);
        if (m) lastIndexByKey.set(m[1], idx);
      });
      const deduped = lines.filter((line, idx) => {
        const m = line.match(/^([A-Za-z][\w.-]*)=/);
        if (!m) return true;
        return lastIndexByKey.get(m[1]) === idx;
      });
      text = deduped.join("\n");

      // 2d. Гарантируем все обязательные свойства
      for (const [key, value] of Object.entries(REQUIRED_PROPERTIES)) {
        const re = new RegExp(`^${key.replace(/\./g, "\\.")}=.*$`, "m");
        if (re.test(text)) {
          text = text.replace(re, `${key}=${value}`);
        } else {
          if (text.length > 0 && !text.endsWith("\n")) text += "\n";
          text += `${key}=${value}\n`;
        }
      }

      // 2e. Файл ОБЯЗАН заканчиваться переводом строки
      text = text.replace(/\n*$/, "\n");

      fs.writeFileSync(propsPath, text, "utf8");
      return config;
    }
  ]);

  // 3. Fix settings.gradle — убираем foojay-resolver и добавляем репозитории
  config = withDangerousMod(config, [
    "android",
    async (config) => {
      const settingsPath = path.join(config.modRequest.platformProjectRoot, "settings.gradle");
      if (!fs.existsSync(settingsPath)) return config;

      let text = fs.readFileSync(settingsPath, "utf8");

      // Убираем foojay-resolver-convention plugin (вызывает 403 в CBE)
      text = text.replace(/^[ \t]*id\s*\(?\s*["']org\.gradle\.toolchains\.foojay-resolver-convention["'].*\n?/gm, "");

      // Добавляем mavenCentral() и gradlePluginPortal() в pluginManagement
      if (text.includes("pluginManagement")) {
        if (!text.includes("mavenCentral()")) {
          text = text.replace(
            /pluginManagement\s*\{([^}]*)repositories\s*\{/,
            `pluginManagement {$1repositories {\n        mavenCentral()`
          );
        }
        if (!text.includes("gradlePluginPortal()")) {
          text = text.replace(
            /pluginManagement\s*\{([^}]*)repositories\s*\{/,
            `pluginManagement {$1repositories {\n        gradlePluginPortal()`
          );
        }
      }

      // Добавляем buildCache disable
      if (!text.includes("buildCache")) {
        text += `\n\nbuildCache {\n    local {\n        enabled = false\n    }\n}\n`;
      }

      fs.writeFileSync(settingsPath, text, "utf8");
      return config;
    }
  ]);

  return config;
}

module.exports = withFixGradleProperties;
