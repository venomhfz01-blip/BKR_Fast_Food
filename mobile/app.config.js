export default {
    expo: {
          owner: 'hfzkhans-team',
          name: 'BKR Fast Food Monitor',
          slug: 'hfz-khan',
          version: '1.0.0',
          android: { package: 'com.bkr.fastfood.monitor' },
          ios: { bundleIdentifier: 'com.bkr.fastfood.monitor' },
          plugins: [['expo-build-properties', { android: { kotlinVersion: '1.9.25' } }]],
          extra: {
                  apiUrl: process.env.EXPO_PUBLIC_API_URL || 'https://bkr-fastfood-backend-6snv.onrender.com/api',
                  eas: { projectId: 'c3870fd5-8594-47f1-8265-0373e8e7f408' }
          }
    }
};
