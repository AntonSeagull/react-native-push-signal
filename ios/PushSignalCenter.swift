import Foundation
import ObjectiveC
import UIKit
import UserNotifications

@objc(PushSignalCenter)
final class PushSignalCenter: NSObject, UNUserNotificationCenterDelegate {
  @objc static let shared = PushSignalCenter()

  @objc static func bootstrap(launchOptions: [AnyHashable: Any]?) {
    shared.pendingLaunchOptions = launchOptions
    shared.install()
  }

  private let lock = NSLock()
  private var didInstall = false
  private var deviceToken: String?
  private var registrationError: Error?
  private var tokenWaiters: [(Result<String, Error>) -> Void] = []
  private var pendingPress: PushMessage?
  private var pendingLaunchOptions: [AnyHashable: Any]?
  private var didCaptureLaunchNotification = false

  var onMessage: ((PushMessage) -> Void)?
  var onNotificationPress: ((PushMessage) -> Void)? {
    didSet {
      flushPendingPress()
    }
  }

  func install() {
    DispatchQueue.main.async {
      self.installOnMain()
    }
  }

  private func installOnMain() {
    guard !didInstall else {
      captureLaunchNotificationIfNeeded()
      return
    }

    didInstall = true
    UNUserNotificationCenter.current().delegate = self
    swizzleAppDelegate()
    captureLaunchNotificationIfNeeded()
  }

  func permissionStatus() async -> PermissionStatus {
    await withCheckedContinuation { continuation in
      UNUserNotificationCenter.current().getNotificationSettings { settings in
        continuation.resume(returning: Self.map(settings.authorizationStatus))
      }
    }
  }

  func requestPermission() async -> PermissionStatus {
    do {
      let granted = try await UNUserNotificationCenter.current()
        .requestAuthorization(options: [.alert, .badge, .sound])
      if granted {
        return await permissionStatus()
      }

      return await permissionStatus()
    } catch {
      return .denied
    }
  }

  func fetchCredentials() async throws -> PushCredentials {
    await MainActor.run {
      self.installOnMain()
      UIApplication.shared.registerForRemoteNotifications()
    }

    let token = try await waitForToken()
    return PushCredentials(
      platform: .ios,
      token: token,
      environment: currentEnvironment()
    )
  }

  func handleDeviceToken(_ deviceToken: Data) {
    let token = deviceToken.map { String(format: "%02.2hhx", $0) }.joined()
    finishRegistration(result: .success(token))
  }

  func handleRegistrationError(_ error: Error) {
    finishRegistration(result: .failure(error))
  }

  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    emitMessage(Self.message(from: notification))
    completionHandler([])
  }

  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    emitPress(Self.message(from: response.notification))
    completionHandler()
  }

  private func waitForToken() async throws -> String {
    if let deviceToken {
      return deviceToken
    }

    if let registrationError {
      throw registrationError
    }

    return try await withCheckedThrowingContinuation { continuation in
      var didResume = false
      let finish: (Result<String, Error>) -> Void = { result in
        guard !didResume else {
          return
        }
        didResume = true
        continuation.resume(with: result)
      }

      lock.lock()
      if let deviceToken {
        lock.unlock()
        finish(.success(deviceToken))
        return
      }
      if let registrationError {
        lock.unlock()
        finish(.failure(registrationError))
        return
      }
      tokenWaiters.append(finish)
      lock.unlock()

      DispatchQueue.main.asyncAfter(deadline: .now() + 20) {
        let timeout = NSError(
          domain: "PushSignal",
          code: 1,
          userInfo: [
            NSLocalizedDescriptionKey:
              "APNs registration timed out. Use a physical iOS device and enable the Push Notifications capability.",
          ]
        )
        PushSignalCenter.shared.finishRegistration(result: .failure(timeout))
      }
    }
  }

  private func finishRegistration(result: Result<String, Error>) {
    lock.lock()
    switch result {
    case let .success(token):
      deviceToken = token
      registrationError = nil
    case let .failure(error):
      if deviceToken == nil {
        registrationError = error
      }
    }
    let waiters = tokenWaiters
    tokenWaiters.removeAll()
    lock.unlock()

    waiters.forEach { $0(result) }
  }

  private func emitMessage(_ message: PushMessage) {
    DispatchQueue.main.async {
      self.onMessage?(message)
    }
  }

  private func emitPress(_ message: PushMessage) {
    DispatchQueue.main.async {
      if let onNotificationPress = self.onNotificationPress {
        onNotificationPress(message)
      } else {
        self.pendingPress = message
      }
    }
  }

  private func flushPendingPress() {
    DispatchQueue.main.async {
      guard let pendingPress = self.pendingPress, let onNotificationPress = self.onNotificationPress else {
        return
      }

      self.pendingPress = nil
      onNotificationPress(pendingPress)
    }
  }

  private func captureLaunchNotificationIfNeeded() {
    guard !didCaptureLaunchNotification else {
      return
    }

    if pendingLaunchOptions == nil {
      pendingLaunchOptions = PushSignalCopyLaunchOptions() as? [AnyHashable: Any]
    }

    let launchOptions = pendingLaunchOptions
    guard let launchOptions else {
      return
    }

    didCaptureLaunchNotification = true

    let key = UIApplication.LaunchOptionsKey.remoteNotification
    let payload = launchOptions[key] as? [AnyHashable: Any]
      ?? launchOptions[key.rawValue] as? [AnyHashable: Any]
    guard let payload else {
      return
    }

    emitPress(Self.message(from: payload, fallbackId: "launch"))
  }

  private func currentEnvironment() -> PushEnvironment {
    if let environment = Self.apsEnvironment() {
      return environment
    }

#if DEBUG
    return .sandbox
#else
    return .production
#endif
  }

  private static func apsEnvironment() -> PushEnvironment? {
    guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
          let data = try? Data(contentsOf: url),
          let contents = String(data: data, encoding: .ascii),
          let regex = try? NSRegularExpression(
            pattern: "<key>aps-environment</key>\\s*<string>(\\w+)</string>"
          )
    else {
      return nil
    }

    let range = NSRange(contents.startIndex..<contents.endIndex, in: contents)
    guard let match = regex.firstMatch(in: contents, range: range),
          let valueRange = Range(match.range(at: 1), in: contents)
    else {
      return nil
    }

    switch contents[valueRange] {
    case "development":
      return .sandbox
    case "production":
      return .production
    default:
      return nil
    }
  }

  private static func map(_ status: UNAuthorizationStatus) -> PermissionStatus {
    switch status {
    case .authorized, .provisional, .ephemeral:
      return .authorized
    case .denied:
      return .denied
    case .notDetermined:
      return .notdetermined
    @unknown default:
      return .denied
    }
  }

  private static func message(from notification: UNNotification) -> PushMessage {
    let content = notification.request.content
    return message(
      from: content.userInfo,
      fallbackId: notification.request.identifier,
      title: content.title.isEmpty ? nil : content.title,
      body: content.body.isEmpty ? nil : content.body
    )
  }

  private static func message(
    from userInfo: [AnyHashable: Any],
    fallbackId: String? = nil,
    title: String? = nil,
    body: String? = nil
  ) -> PushMessage {
    let aps = userInfo["aps"] as? [AnyHashable: Any]
    let alert = aps?["alert"]
    var resolvedTitle = title
    var resolvedBody = body

    if let alertText = alert as? String {
      resolvedBody = resolvedBody ?? alertText
    } else if let alertDict = alert as? [AnyHashable: Any] {
      resolvedTitle = resolvedTitle ?? stringify(alertDict["title"])
      resolvedBody = resolvedBody ?? stringify(alertDict["body"])
    }

    var data: [String: String] = [:]
    for (key, value) in userInfo {
      let stringKey = String(describing: key)
      if stringKey == "aps" {
        continue
      }
      if let stringValue = stringify(value) {
        data[stringKey] = stringValue
      }
    }

    return PushMessage(
      id: stringify(userInfo["gcm.message_id"]) ?? fallbackId,
      title: resolvedTitle,
      body: resolvedBody,
      data: data
    )
  }

  private static func stringify(_ value: Any?) -> String? {
    guard let value else {
      return nil
    }
    if let value = value as? String {
      return value
    }
    if let value = value as? NSNumber {
      return value.stringValue
    }
    if JSONSerialization.isValidJSONObject(value),
       let data = try? JSONSerialization.data(withJSONObject: value),
       let json = String(data: data, encoding: .utf8)
    {
      return json
    }
    return String(describing: value)
  }

  private func swizzleAppDelegate() {
    guard let appDelegate = UIApplication.shared.delegate else {
      return
    }

    let target: AnyClass = type(of: appDelegate)
    let source: AnyClass = PushSignalAppDelegateHook.self

    swizzle(
      target: target,
      original: #selector(UIApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:)),
      replacement: #selector(PushSignalAppDelegateHook.pushSignal_application(_:didRegisterForRemoteNotificationsWithDeviceToken:)),
      source: source
    )
    swizzle(
      target: target,
      original: #selector(UIApplicationDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:)),
      replacement: #selector(PushSignalAppDelegateHook.pushSignal_application(_:didFailToRegisterForRemoteNotificationsWithError:)),
      source: source
    )
  }

  private func swizzle(target: AnyClass, original: Selector, replacement: Selector, source: AnyClass) {
    guard let replacementMethod = class_getInstanceMethod(source, replacement) else {
      return
    }

    let replacementImp = method_getImplementation(replacementMethod)
    let types = method_getTypeEncoding(replacementMethod)

    if class_addMethod(target, original, replacementImp, types) {
      return
    }

    guard class_addMethod(target, replacement, replacementImp, types),
          let originalMethod = class_getInstanceMethod(target, original),
          let addedReplacement = class_getInstanceMethod(target, replacement)
    else {
      return
    }

    method_exchangeImplementations(originalMethod, addedReplacement)
  }
}

private final class PushSignalAppDelegateHook: NSObject {
  @objc func pushSignal_application(
    _ application: UIApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
  ) {
    PushSignalCenter.shared.handleDeviceToken(deviceToken)
    let selector = #selector(
      PushSignalAppDelegateHook.pushSignal_application(_:didRegisterForRemoteNotificationsWithDeviceToken:)
    )
    guard responds(to: selector),
          let method = class_getInstanceMethod(type(of: self), selector)
    else {
      return
    }

    typealias Fn = @convention(c) (Any, Selector, UIApplication, Data) -> Void
    unsafeBitCast(method_getImplementation(method), to: Fn.self)(self, selector, application, deviceToken)
  }

  @objc func pushSignal_application(
    _ application: UIApplication,
    didFailToRegisterForRemoteNotificationsWithError error: Error
  ) {
    PushSignalCenter.shared.handleRegistrationError(error)
    let selector = #selector(
      PushSignalAppDelegateHook.pushSignal_application(_:didFailToRegisterForRemoteNotificationsWithError:)
    )
    guard responds(to: selector),
          let method = class_getInstanceMethod(type(of: self), selector)
    else {
      return
    }

    typealias Fn = @convention(c) (Any, Selector, UIApplication, NSError) -> Void
    unsafeBitCast(method_getImplementation(method), to: Fn.self)(self, selector, application, error as NSError)
  }
}
