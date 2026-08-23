import NitroModules

class PushSignal: HybridPushSignalSpec {
  override init() {
    super.init()
    PushSignalCenter.shared.install()
  }

  func initialize(config: AndroidFirebaseConfig) throws -> Promise<Void> {
    _ = config
    return Promise.async {
      ()
    }
  }

  func getCredentials() throws -> Promise<PushCredentials> {
    return Promise.async {
      try await PushSignalCenter.shared.fetchCredentials()
    }
  }

  func setOnMessage(callback: @escaping (PushMessage) -> Void) throws {
    PushSignalCenter.shared.onMessage = callback
  }

  func setOnNotificationPress(callback: @escaping (PushMessage) -> Void) throws {
    PushSignalCenter.shared.onNotificationPress = callback
  }
}
