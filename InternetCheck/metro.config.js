const { getDefaultConfig } = require("expo/metro-config");

const config = getDefaultConfig(__dirname);
config.resolver.disableHierarchicalLookup = false;
config.watchFolders = [__dirname];

module.exports = config;
