import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.echoworld.mobile',
  appName: 'EchoWorld',
  webDir: 'dist',
  android: {
    allowMixedContent: true,
  },
};

export default config;
