const { withAppBuildGradle } = require("expo/config-plugins");

// Ключ не хранится в исходниках. Если заданы все четыре переменные IC_*, release подписывается ими.
// Если переменных нет (например, облачный сборщик подставляет собственный ключ), плагин ничего не меняет
// и не прерывает сборку.
module.exports = function withReleaseSigning(config) {
  return withAppBuildGradle(config, (config) => {
    let source = config.modResults.contents;
    if (source.includes("// InternetCheck release signing")) return config;
    source += `
// InternetCheck release signing
def icSigningVars = ["IC_KEYSTORE", "IC_STORE_PASSWORD", "IC_KEY_ALIAS", "IC_KEY_PASSWORD"]
if (icSigningVars.every { System.getenv(it) }) {
    android.signingConfigs.create("release") {
        storeFile file(System.getenv("IC_KEYSTORE"))
        storePassword System.getenv("IC_STORE_PASSWORD")
        keyAlias System.getenv("IC_KEY_ALIAS")
        keyPassword System.getenv("IC_KEY_PASSWORD")
    }
    android.buildTypes.release.signingConfig = android.signingConfigs.release
} else {
    logger.lifecycle("InternetCheck: IC_* signing variables not set, release signing left to the build environment")
}
`;
    config.modResults.contents = source;
    return config;
  });
};
