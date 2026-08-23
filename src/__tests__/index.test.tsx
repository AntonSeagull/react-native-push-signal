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

  it('fans out native callbacks and unsubscribes listeners', () => {
    const { onMessage, onNotificationPress } =
      require('../pushSignal.native') as typeof import('../pushSignal.native');

    const message = jest.fn();
    const press = jest.fn();
    const unsubscribeMessage = onMessage(message);
    const unsubscribePress = onNotificationPress(press);

    const onMessageCb = mockHybrid.setOnMessage.mock.calls[0]?.[0] as (
      value: unknown
    ) => void;
    const onPressCb = mockHybrid.setOnNotificationPress.mock.calls[0]?.[0] as (
      value: unknown
    ) => void;

    const payload = { title: 'Hi', data: { a: '1' } };
    onMessageCb(payload);
    onPressCb(payload);

    expect(message).toHaveBeenCalledWith(payload);
    expect(press).toHaveBeenCalledWith(payload);

    unsubscribeMessage();
    unsubscribePress();
    onMessageCb(payload);
    onPressCb(payload);

    expect(message).toHaveBeenCalledTimes(1);
    expect(press).toHaveBeenCalledTimes(1);
  });
});
