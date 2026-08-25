import { beforeEach, describe, expect, it, jest } from '@jest/globals';

type MessageHandler = (message: {
  id?: string;
  title?: string;
  body?: string;
  data: Object;
}) => void;

const messageHandlers: MessageHandler[] = [];
const pressHandlers: MessageHandler[] = [];

const mockNative = {
  initialize: jest.fn(async () => undefined),
  getCredentials: jest.fn(async () => ({
    platform: 'ios',
    token: 'token-1',
    environment: 'sandbox',
  })),
  startListening: jest.fn(),
  onMessage: jest.fn((handler: MessageHandler) => {
    messageHandlers.push(handler);
    return { remove: () => undefined };
  }),
  onNotificationPress: jest.fn((handler: MessageHandler) => {
    pressHandlers.push(handler);
    return { remove: () => undefined };
  }),
};

jest.mock('../NativePushSignal', () => ({
  __esModule: true,
  default: mockNative,
}));

describe('pushSignal', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.resetModules();
    messageHandlers.length = 0;
    pressHandlers.length = 0;
  });

  it('forwards initialize and credential calls to the native module', async () => {
    const { getCredentials, initialize } =
      require('../pushSignal.native') as typeof import('../pushSignal.native');

    const config = {
      project_id: 'my-project',
      mobilesdk_app_id: '1:123456789:android:abcd',
      current_key: 'AIza...',
      project_number: '123456789',
    };

    await initialize(config);
    expect(mockNative.initialize).toHaveBeenCalledWith(config);

    await expect(getCredentials()).resolves.toEqual({
      platform: 'ios',
      token: 'token-1',
      environment: 'sandbox',
    });
  });

  it('fans out native callbacks and unsubscribes listeners', () => {
    const { onMessage, onNotificationPress } =
      require('../pushSignal.native') as typeof import('../pushSignal.native');

    const message = jest.fn(() => undefined);
    const press = jest.fn();
    const unsubscribeMessage = onMessage(message);
    const unsubscribePress = onNotificationPress(press);

    const payload = { title: 'Hi', data: { a: '1' } };
    messageHandlers[0]?.(payload);
    pressHandlers[0]?.(payload);

    expect(message).toHaveBeenCalledWith(payload);
    expect(press).toHaveBeenCalledWith(payload);

    unsubscribeMessage();
    unsubscribePress();
    messageHandlers[0]?.(payload);
    pressHandlers[0]?.(payload);

    expect(message).toHaveBeenCalledTimes(1);
    expect(press).toHaveBeenCalledTimes(1);
  });

  it('delivers the message even when a listener throws', () => {
    const { onMessage } =
      require('../pushSignal.native') as typeof import('../pushSignal.native');

    const ok = jest.fn(() => undefined);
    onMessage(() => {
      throw new Error('nope');
    });
    onMessage(ok);

    expect(() => messageHandlers[0]?.({ title: 'Hi', data: {} })).not.toThrow();
    expect(ok).toHaveBeenCalledTimes(1);
  });

  it('calls startListening when binding native callbacks', () => {
    require('../pushSignal.native');
    expect(mockNative.startListening).toHaveBeenCalled();
  });
});
