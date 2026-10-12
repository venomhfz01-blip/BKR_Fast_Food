export default {
  expo: {
    owner: 'hfz_khan01',
    name: 'BKR Fast Food Monitor',
    slug: 'bkr-fast-food-monitor',
    version: '1.0.0',
    android: { package: 'com.bkr.fastfood.monitor' },
    ios: { bundleIdentifier: 'com.bkr.fastfood.monitor' },
    plugins: [['expo-build-properties', { android: { kotlinVersion: '1.9.25' } }]],
    extra: {
      apiUrl: process.env.EXPO_PUBLIC_API_URL || 'https://bkr-fastfood-backend-6snv.onrender.com/api',
      eas: { projectId: '22a6bc1d-04a2-477d-900f-e5871abad1dd' }
    }
  }
};
