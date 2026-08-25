import { useEffect, useState } from 'react';
import {
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  getCredentials,
  initialize,
  onMessage,
  onNotificationPress,
  type PushCredentials,
  type PushMessage,
} from 'react-native-push-signal';

export default function App() {
  const [credentials, setCredentials] = useState<PushCredentials | null>(null);
  const [log, setLog] = useState<string[]>([]);

  const append = (line: string) => {
    setLog((current) => [line, ...current].slice(0, 30));
  };

  useEffect(() => {
    const unsubscribeMessage = onMessage((message: PushMessage) => {
      append(`message: ${JSON.stringify(message)}`);
      return true;
    });
    const unsubscribePress = onNotificationPress((message: PushMessage) => {
      append(`press: ${JSON.stringify(message)}`);
    });

    return () => {
      unsubscribeMessage();
      unsubscribePress();
    };
  }, []);

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView contentContainerStyle={styles.container}>
        <Text style={styles.title}>Push Signal</Text>
        <Text style={styles.row}>
          Credentials: {credentials ? JSON.stringify(credentials) : 'none'}
        </Text>
        <View style={styles.actions}>
          <Action
            label="Get credentials"
            onPress={async () => {
              try {
                await initialize({
                  project_id: 'my-project',
                  mobilesdk_app_id: '1:123456789:android:abcd',
                  current_key: 'AIza...',
                  project_number: '123456789',
                });
                const next = await getCredentials();
                setCredentials(next);
                append(`credentials: ${JSON.stringify(next)}`);
              } catch (error) {
                append(
                  `credentials error: ${
                    error instanceof Error ? error.message : String(error)
                  }`
                );
              }
            }}
          />
        </View>
        <Text style={styles.subtitle}>Log</Text>
        {log.map((line, index) => (
          <Text key={`${index}-${line}`} style={styles.log}>
            {line}
          </Text>
        ))}
      </ScrollView>
    </SafeAreaView>
  );
}

function Action({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} style={styles.button}>
      <Text style={styles.buttonLabel}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#111',
  },
  container: {
    padding: 20,
    gap: 12,
  },
  title: {
    color: '#fff',
    fontSize: 28,
    fontWeight: '700',
  },
  subtitle: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '600',
    marginTop: 8,
  },
  row: {
    color: '#ddd',
    fontSize: 15,
  },
  actions: {
    gap: 8,
  },
  button: {
    backgroundColor: '#3b82f6',
    borderRadius: 10,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  buttonLabel: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
    textAlign: 'center',
  },
  log: {
    color: '#9ca3af',
    fontFamily: 'Courier',
    fontSize: 12,
  },
});
