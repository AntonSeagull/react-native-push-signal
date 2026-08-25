import { beforeEach, describe, expect, it, jest } from '@jest/globals';

const mockHybrid = {
  initialize: jest.fn(async () => undefined),
  getCredentials: jest.fn(async () => ({
    platform: 'ios' as const,
    token: 'token-1',
    environment: 'sandbox' as const,
  })),
  setOnMessage: jest.fn(),
  setOnNotificationPress: jest.fn(),
};

jest.mock('react-native-nitro-modules', () => ({
  NitroModules: {
    createHybridObject: () => mockHybrid,
  },
}));

describe('pushSignal', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.resetModules();
  });

  it('forwards initialize and credential calls to the hybrid object', async () => {
    const { getCredentials, initialize } =
      require('../pushSignal.native') as typeof import('../pushSignal.native');

    const config = {
      project_id: 'my-project',
      mobilesdk_app_id: '1:123456789:android:abcd',
      current_key: 'AIza...',
      project_number: '123456789',
    };

    await initialize(config);
    expect(mockHybrid.initialize).toHaveBeenCalledWith(config);

    await expect(getCredentials()).resolves.toEqual({
      platform: 'ios',
      token: 'token-1',
      environment: 'sandbox',
    });
  });

  it('fans out native callbacks and unsubscribes listeners', async () => {
    const { onMessage, onNotificationPress } =
      require('../pushSignal.native') as typeof import('../pushSignal.native');

    const message = jest.fn(() => undefined);
    const press = jest.fn();
    const unsubscribeMessage = onMessage(message);
    const unsubscribePress = onNotificationPress(press);

    const onMessageCb = mockHybrid.setOnMessage.mock.calls[0]?.[0] as (
      value: unknown
    ) => Promise<boolean>;
    const onPressCb = mockHybrid.setOnNotificationPress.mock.calls[0]?.[0] as (
      value: unknown
    ) => void;

    const payload = { title: 'Hi', data: { a: '1' } };
    await expect(onMessageCb(payload)).resolves.toBe(false);
    onPressCb(payload);

    expect(message).toHaveBeenCalledWith(payload);
    expect(press).toHaveBeenCalledWith(payload);

    unsubscribeMessage();
    unsubscribePress();
    await expect(onMessageCb(payload)).resolves.toBe(false);
    onPressCb(payload);

    expect(message).toHaveBeenCalledTimes(1);
    expect(press).toHaveBeenCalledTimes(1);
  });

  it('shows a foreground banner when any onMessage listener returns true', async () => {
    const { onMessage } =
      require('../pushSignal.native') as typeof import('../pushSignal.native');

    onMessage(() => undefined);
    onMessage(() => true);

    const onMessageCb = mockHybrid.setOnMessage.mock.calls[0]?.[0] as (
      value: unknown
    ) => Promise<boolean>;

    await expect(onMessageCb({ title: 'Hi', data: {} })).resolves.toBe(true);
  });

  it('does not show a banner when onMessage throws or returns nothing', async () => {
    const { onMessage } =
      require('../pushSignal.native') as typeof import('../pushSignal.native');

    onMessage(() => {
      throw new Error('nope');
    });

    const onMessageCb = mockHybrid.setOnMessage.mock.calls[0]?.[0] as (
      value: unknown
    ) => Promise<boolean>;

    await expect(onMessageCb({ title: 'Hi', data: {} })).resolves.toBe(false);
  });
});
