import { beforeEach, describe, expect, it, jest } from '@jest/globals';

const mockHybrid = {
  initialize: jest.fn(),
  getPermissionStatus: jest.fn(async () => 'denied' as const),
  requestPermission: jest.fn(async () => 'authorized' as const),
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

  it('forwards permission and credential calls to the hybrid object', async () => {
    const {
      getCredentials,
      getPermissionStatus,
      initialize,
      requestPermission,
    } =
      require('../pushSignal.native') as typeof import('../pushSignal.native');

    initialize({ projectId: 'demo' });
    expect(mockHybrid.initialize).toHaveBeenCalledWith({ projectId: 'demo' });

    await expect(getPermissionStatus()).resolves.toBe('denied');
    await expect(requestPermission()).resolves.toBe('authorized');
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
