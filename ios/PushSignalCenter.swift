import Foundation
import NitroModules
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

  /// Called from ObjC `+load` / launch notifications so the UNUserNotificationCenter
  /// delegate is set before the app finishes launching. If this happens after JS loads,
  /// iOS never calls `willPresent` and foreground pushes never reach JS.
  @objc static func installEarly() {
    shared.install()
  }

  private let lock = NSLock()
  private var didInstall = false
  private var didSwizzleAppDelegate = false
  private var didSwizzleNotificationCenter = false
  private var deviceToken: String?
  private var registrationError: Error?
  private var tokenWaiters: [(Result<String, Error>) -> Void] = []
  private var pendingPress: PushMessage?
  private var pendingMessages: [PushMessage] = []
  private var pendingLaunchOptions: [AnyHashable: Any]?
  private var didCaptureLaunchNotification = false
  private var recentMessageIds: [String: Date] = [:]
  private weak var forwardingDelegate: UNUserNotificationCenterDelegate?

  var onMessage: ((PushMessage) -> Void)? {
    didSet {
      flushPendingMessages()
    }
  }
  var onNotificationPress: ((PushMessage) -> Void)? {
    didSet {
      flushPendingPress()
    }
  }

  func install() {
    if Thread.isMainThread {
      installOnMain()
    } else {
      DispatchQueue.main.async {
        self.installOnMain()
      }
    }
  }

  private func installOnMain() {
    swizzleNotificationCenterDelegateIfNeeded()

    guard !didInstall else {
      UNUserNotificationCenter.current().delegate = self
      swizzleAppDelegate()
      captureLaunchNotificationIfNeeded()
      return
    }

    didInstall = true
    UNUserNotificationCenter.current().delegate = self
    swizzleAppDelegate()
    captureLaunchNotificationIfNeeded()
  }

  func rememberForwardingDelegate(_ delegate: UNUserNotificationCenterDelegate?) {
    if let delegate, delegate !== self {
      forwardingDelegate = delegate
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
    let token = deviceToken.map { String(format: "%02x", $0) }.joined()
    finishRegistration(result: .success(token))
  }

  func handleRegistrationError(_ error: Error) {
    finishRegistration(result: .failure(error))
  }

  @objc func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    deliverMessage(Self.message(from: notification))
    forwardWillPresent(
      center,
      notification: notification,
      ourOptions: Self.foregroundPresentationOptions,
      completionHandler: completionHandler
    )
  }

  @objc func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    emitPress(Self.message(from: response.notification))
    if forwardingDelegate?.responds(
      to: #selector(UNUserNotificationCenterDelegate.userNotificationCenter(_:didReceive:withCompletionHandler:))
    ) == true {
      forwardingDelegate?.userNotificationCenter?(center, didReceive: response, withCompletionHandler: completionHandler)
    } else {
      completionHandler()
    }
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

  private func synchronized<T>(_ body: () -> T) -> T {
    lock.lock()
    defer { lock.unlock() }
    return body()
  }

  private func deliverMessage(_ message: PushMessage) {
    guard shouldEmit(message) else {
      return
    }

    let callback = synchronized { onMessage }
    guard let callback else {
      synchronized { pendingMessages.append(message) }
      return
    }

    emitToJs(callback, message: message)
  }

  private func emitToJs(
    _ callback: @escaping (PushMessage) -> Void,
    message: PushMessage
  ) {
    DispatchQueue.main.async {
      callback(message)
    }
  }

  private func flushPendingMessages() {
    DispatchQueue.main.async {
      let callback = self.synchronized { self.onMessage }
      let queued = self.synchronized { () -> [PushMessage] in
        let messages = self.pendingMessages
        self.pendingMessages.removeAll()
        return messages
      }
      guard let callback, !queued.isEmpty else {
        return
      }

      for message in queued {
        self.emitToJs(callback, message: message)
      }
    }
  }

  private func shouldEmit(_ message: PushMessage) -> Bool {
    let key = message.id ?? "\(message.title ?? "")|\(message.body ?? "")"
    return synchronized {
      let now = Date()
      recentMessageIds = recentMessageIds.filter { now.timeIntervalSince($0.value) < 5 }
      if let last = recentMessageIds[key], now.timeIntervalSince(last) < 2 {
        return false
      }
      recentMessageIds[key] = now
      return true
    }
  }

  private func forwardWillPresent(
    _ center: UNUserNotificationCenter,
    notification: UNNotification,
    ourOptions: UNNotificationPresentationOptions,
    completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    let selector = #selector(
      UNUserNotificationCenterDelegate.userNotificationCenter(_:willPresent:withCompletionHandler:)
    )
    guard let forwardingDelegate, forwardingDelegate.responds(to: selector) else {
      completionHandler(ourOptions)
      return
    }

    forwardingDelegate.userNotificationCenter?(
      center,
      willPresent: notification,
      withCompletionHandler: { (forwarded: Int) in
        // ObjC optional-protocol bridging exposes options as Int here.
        let forwardedOptions = UNNotificationPresentationOptions(rawValue: UInt(forwarded))
        completionHandler(ourOptions.union(forwardedOptions))
      }
    )
  }

  private static var foregroundPresentationOptions: UNNotificationPresentationOptions {
    if #available(iOS 14.0, *) {
      return [.banner, .list, .sound, .badge]
    }
    return [.alert, .sound, .badge]
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
    guard !didSwizzleAppDelegate else {
      return
    }
    guard let appDelegate = UIApplication.shared.delegate else {
      return
    }
    didSwizzleAppDelegate = true

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

  private func swizzleNotificationCenterDelegateIfNeeded() {
    guard !didSwizzleNotificationCenter else {
      return
    }
    didSwizzleNotificationCenter = true

    let target: AnyClass = UNUserNotificationCenter.self
    let original = #selector(setter: UNUserNotificationCenter.delegate)
    let replacement = #selector(UNUserNotificationCenter.pushSignal_setDelegate(_:))

    guard let originalMethod = class_getInstanceMethod(target, original),
          let replacementMethod = class_getInstanceMethod(target, replacement)
    else {
      return
    }

    method_exchangeImplementations(originalMethod, replacementMethod)
  }

  private func swizzle(target: AnyClass, original: Selector, replacement: Selector, source: AnyClass) {
    guard let replacementMethod = class_getInstanceMethod(source, replacement) else {
      return
    }

    let replacementImp = method_getImplementation(replacementMethod)
    let types = method_getTypeEncoding(replacementMethod)
    let superMethod = class_getSuperclass(target).flatMap { class_getInstanceMethod($0, original) }

    // No implementation on this class yet — install ours and keep superclass under `replacement`.
    if class_addMethod(target, original, replacementImp, types) {
      if let superMethod {
        class_addMethod(
          target,
          replacement,
          method_getImplementation(superMethod),
          method_getTypeEncoding(superMethod)
        )
      }
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
    forwardToOriginal(
      hooked: #selector(
        UIApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:)
      ),
      original: #selector(
        PushSignalAppDelegateHook.pushSignal_application(_:didRegisterForRemoteNotificationsWithDeviceToken:)
      ),
      application: application,
      deviceToken: deviceToken
    )
  }

  @objc func pushSignal_application(
    _ application: UIApplication,
    didFailToRegisterForRemoteNotificationsWithError error: Error
  ) {
    PushSignalCenter.shared.handleRegistrationError(error)
    forwardToOriginal(
      hooked: #selector(
        UIApplicationDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:)
      ),
      original: #selector(
        PushSignalAppDelegateHook.pushSignal_application(_:didFailToRegisterForRemoteNotificationsWithError:)
      ),
      application: application,
      error: error as NSError
    )
  }

  private func forwardToOriginal(
    hooked: Selector,
    original: Selector,
    application: UIApplication,
    deviceToken: Data
  ) {
    guard responds(to: original),
          let cls = object_getClass(self),
          let originalImp = class_getMethodImplementation(cls, original),
          let hookedImp = class_getMethodImplementation(cls, hooked),
          originalImp != hookedImp
    else {
      return
    }

    typealias Fn = @convention(c) (Any, Selector, UIApplication, Data) -> Void
    unsafeBitCast(originalImp, to: Fn.self)(self, original, application, deviceToken)
  }

  private func forwardToOriginal(
    hooked: Selector,
    original: Selector,
    application: UIApplication,
    error: NSError
  ) {
    guard responds(to: original),
          let cls = object_getClass(self),
          let originalImp = class_getMethodImplementation(cls, original),
          let hookedImp = class_getMethodImplementation(cls, hooked),
          originalImp != hookedImp
    else {
      return
    }

    typealias Fn = @convention(c) (Any, Selector, UIApplication, NSError) -> Void
    unsafeBitCast(originalImp, to: Fn.self)(self, original, application, error)
  }
}

extension UNUserNotificationCenter {
  @objc func pushSignal_setDelegate(_ delegate: UNUserNotificationCenterDelegate?) {
    if let delegate, delegate is PushSignalCenter {
      pushSignal_setDelegate(delegate)
      return
    }

    PushSignalCenter.shared.rememberForwardingDelegate(delegate)
    pushSignal_setDelegate(PushSignalCenter.shared)
  }
}
