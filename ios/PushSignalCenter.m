#import "PushSignalCenter.h"
#import "PushSignalLaunchStore.h"
#import <ObjectiveC/runtime.h>
#import <UIKit/UIKit.h>

@interface PushSignalAppDelegateHook : NSObject
@end

@interface UNUserNotificationCenter (PushSignal)
- (void)pushSignal_setDelegate:(id<UNUserNotificationCenterDelegate>)delegate;
@end

@implementation PushSignalCenter {
  NSLock *_lock;
  BOOL _didInstall;
  BOOL _didSwizzleAppDelegate;
  BOOL _didSwizzleNotificationCenter;
  NSString *_deviceToken;
  NSError *_registrationError;
  NSMutableArray<void (^)(NSString *_Nullable, NSError *_Nullable)> *_tokenWaiters;
  NSDictionary *_pendingPress;
  NSMutableArray<NSDictionary *> *_pendingMessages;
  NSDictionary *_pendingLaunchOptions;
  BOOL _didCaptureLaunchNotification;
  NSMutableDictionary<NSString *, NSDate *> *_recentMessageIds;
  __weak id<UNUserNotificationCenterDelegate> _forwardingDelegate;
  void (^_onMessage)(NSDictionary *);
  void (^_onNotificationPress)(NSDictionary *);
}

+ (instancetype)shared {
  static PushSignalCenter *sharedInstance = nil;
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    sharedInstance = [[PushSignalCenter alloc] init];
  });
  return sharedInstance;
}

+ (void)installEarly {
  [[PushSignalCenter shared] install];
}

+ (void)bootstrapWithLaunchOptions:(NSDictionary *)launchOptions {
  PushSignalCenter *center = [PushSignalCenter shared];
  center->_pendingLaunchOptions = [launchOptions copy];
  [center install];
}

- (instancetype)init {
  if (self = [super init]) {
    _lock = [[NSLock alloc] init];
    _tokenWaiters = [NSMutableArray array];
    _pendingMessages = [NSMutableArray array];
    _recentMessageIds = [NSMutableDictionary dictionary];
  }
  return self;
}

- (void)install {
  if ([NSThread isMainThread]) {
    [self installOnMain];
  } else {
    dispatch_async(dispatch_get_main_queue(), ^{
      [self installOnMain];
    });
  }
}

- (void)installOnMain {
  [self swizzleNotificationCenterDelegateIfNeeded];

  if (_didInstall) {
    [UNUserNotificationCenter currentNotificationCenter].delegate = self;
    [self swizzleAppDelegate];
    [self captureLaunchNotificationIfNeeded];
    return;
  }

  _didInstall = YES;
  [UNUserNotificationCenter currentNotificationCenter].delegate = self;
  [self swizzleAppDelegate];
  [self captureLaunchNotificationIfNeeded];
}

- (void)rememberForwardingDelegate:(id<UNUserNotificationCenterDelegate>)delegate {
  if (delegate != nil && delegate != self) {
    _forwardingDelegate = delegate;
  }
}

- (void)setOnMessage:(void (^)(NSDictionary *))callback {
  _onMessage = [callback copy];
  [self flushPendingMessages];
}

- (void)setOnNotificationPress:(void (^)(NSDictionary *))callback {
  _onNotificationPress = [callback copy];
  [self flushPendingPress];
}

- (void)fetchCredentialsWithResolver:(void (^)(NSDictionary *))resolve
                            rejecter:(void (^)(NSError *))reject {
  dispatch_async(dispatch_get_main_queue(), ^{
    [self installOnMain];
    [[UIApplication sharedApplication] registerForRemoteNotifications];

    [self waitForTokenWithCompletion:^(NSString *token, NSError *error) {
      if (error != nil) {
        reject(error);
        return;
      }
      NSMutableDictionary *credentials = [NSMutableDictionary dictionary];
      credentials[@"platform"] = @"ios";
      credentials[@"token"] = token ?: @"";
      credentials[@"environment"] = [self currentEnvironment];
      resolve(credentials);
    }];
  });
}

- (void)handleDeviceToken:(NSData *)deviceToken {
  const unsigned char *bytes = (const unsigned char *)deviceToken.bytes;
  NSMutableString *token = [NSMutableString stringWithCapacity:deviceToken.length * 2];
  for (NSUInteger i = 0; i < deviceToken.length; i++) {
    [token appendFormat:@"%02x", bytes[i]];
  }
  [self finishRegistrationWithToken:token error:nil];
}

- (void)handleRegistrationError:(NSError *)error {
  [self finishRegistrationWithToken:nil error:error];
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
  [self deliverMessage:[PushSignalCenter messageFromNotification:notification]];
  [self forwardWillPresent:center
              notification:notification
                ourOptions:[PushSignalCenter foregroundPresentationOptions]
         completionHandler:completionHandler];
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
didReceiveNotificationResponse:(UNNotificationResponse *)response
         withCompletionHandler:(void (^)(void))completionHandler {
  [self emitPress:[PushSignalCenter messageFromNotification:response.notification]];
  SEL selector = @selector(userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:);
  id<UNUserNotificationCenterDelegate> forwarding = _forwardingDelegate;
  if (forwarding != nil && [forwarding respondsToSelector:selector]) {
    [forwarding userNotificationCenter:center
        didReceiveNotificationResponse:response
                 withCompletionHandler:completionHandler];
  } else {
    completionHandler();
  }
}

#pragma mark - Token

- (void)waitForTokenWithCompletion:(void (^)(NSString *_Nullable, NSError *_Nullable))completion {
  [_lock lock];
  if (_deviceToken != nil) {
    NSString *token = _deviceToken;
    [_lock unlock];
    completion(token, nil);
    return;
  }
  if (_registrationError != nil) {
    NSError *error = _registrationError;
    [_lock unlock];
    completion(nil, error);
    return;
  }
  [_tokenWaiters addObject:[completion copy]];
  [_lock unlock];

  __weak PushSignalCenter *weakSelf = self;
  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(20 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    NSError *timeout = [NSError errorWithDomain:@"PushSignal"
                                           code:1
                                       userInfo:@{
                                         NSLocalizedDescriptionKey :
                                             @"APNs registration timed out. Use a physical iOS device and enable the Push Notifications capability."
                                       }];
    [weakSelf finishRegistrationWithToken:nil error:timeout];
  });
}

- (void)finishRegistrationWithToken:(NSString *)token error:(NSError *)error {
  [_lock lock];
  if (token != nil) {
    _deviceToken = token;
    _registrationError = nil;
  } else if (_deviceToken == nil) {
    _registrationError = error;
  }
  NSArray *waiters = [_tokenWaiters copy];
  [_tokenWaiters removeAllObjects];
  [_lock unlock];

  for (void (^waiter)(NSString *, NSError *) in waiters) {
    waiter(token, error);
  }
}

#pragma mark - Messages

- (void)deliverMessage:(NSDictionary *)message {
  if (![self shouldEmit:message]) {
    return;
  }

  void (^callback)(NSDictionary *) = nil;
  [_lock lock];
  callback = _onMessage;
  if (callback == nil) {
    [_pendingMessages addObject:message];
    [_lock unlock];
    return;
  }
  [_lock unlock];

  [self emitToJs:callback message:message];
}

- (void)emitToJs:(void (^)(NSDictionary *))callback message:(NSDictionary *)message {
  dispatch_async(dispatch_get_main_queue(), ^{
    callback(message);
  });
}

- (void)flushPendingMessages {
  dispatch_async(dispatch_get_main_queue(), ^{
    void (^callback)(NSDictionary *) = nil;
    NSArray<NSDictionary *> *queued = nil;
    [self->_lock lock];
    callback = self->_onMessage;
    queued = [self->_pendingMessages copy];
    [self->_pendingMessages removeAllObjects];
    [self->_lock unlock];

    if (callback == nil || queued.count == 0) {
      return;
    }
    for (NSDictionary *message in queued) {
      [self emitToJs:callback message:message];
    }
  });
}

- (BOOL)shouldEmit:(NSDictionary *)message {
  NSString *messageId = message[@"id"];
  NSString *title = message[@"title"] ?: @"";
  NSString *body = message[@"body"] ?: @"";
  NSString *key = messageId ?: [NSString stringWithFormat:@"%@|%@", title, body];

  [_lock lock];
  NSDate *now = [NSDate date];
  NSMutableArray<NSString *> *expired = [NSMutableArray array];
  [_recentMessageIds enumerateKeysAndObjectsUsingBlock:^(NSString *k, NSDate *date, BOOL *stop) {
    if ([now timeIntervalSinceDate:date] >= 5) {
      [expired addObject:k];
    }
  }];
  [_recentMessageIds removeObjectsForKeys:expired];

  NSDate *last = _recentMessageIds[key];
  if (last != nil && [now timeIntervalSinceDate:last] < 2) {
    [_lock unlock];
    return NO;
  }
  _recentMessageIds[key] = now;
  [_lock unlock];
  return YES;
}

- (void)forwardWillPresent:(UNUserNotificationCenter *)center
              notification:(UNNotification *)notification
                ourOptions:(UNNotificationPresentationOptions)ourOptions
         completionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
  SEL selector = @selector(userNotificationCenter:willPresentNotification:withCompletionHandler:);
  id<UNUserNotificationCenterDelegate> forwarding = _forwardingDelegate;
  if (forwarding == nil || ![forwarding respondsToSelector:selector]) {
    completionHandler(ourOptions);
    return;
  }

  [forwarding userNotificationCenter:center
             willPresentNotification:notification
               withCompletionHandler:^(UNNotificationPresentationOptions forwarded) {
                 completionHandler(ourOptions | forwarded);
               }];
}

+ (UNNotificationPresentationOptions)foregroundPresentationOptions {
  if (@available(iOS 14.0, *)) {
    return UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList |
           UNNotificationPresentationOptionSound | UNNotificationPresentationOptionBadge;
  }
  return UNNotificationPresentationOptionAlert | UNNotificationPresentationOptionSound |
         UNNotificationPresentationOptionBadge;
}

- (void)emitPress:(NSDictionary *)message {
  dispatch_async(dispatch_get_main_queue(), ^{
    if (self->_onNotificationPress != nil) {
      self->_onNotificationPress(message);
    } else {
      self->_pendingPress = message;
    }
  });
}

- (void)flushPendingPress {
  dispatch_async(dispatch_get_main_queue(), ^{
    if (self->_pendingPress == nil || self->_onNotificationPress == nil) {
      return;
    }
    NSDictionary *pending = self->_pendingPress;
    self->_pendingPress = nil;
    self->_onNotificationPress(pending);
  });
}

- (void)captureLaunchNotificationIfNeeded {
  if (_didCaptureLaunchNotification) {
    return;
  }

  if (_pendingLaunchOptions == nil) {
    _pendingLaunchOptions = PushSignalCopyLaunchOptions();
  }

  NSDictionary *launchOptions = _pendingLaunchOptions;
  if (launchOptions == nil) {
    return;
  }

  _didCaptureLaunchNotification = YES;

  id payload = launchOptions[UIApplicationLaunchOptionsRemoteNotificationKey];
  if (![payload isKindOfClass:[NSDictionary class]]) {
    return;
  }

  [self emitPress:[PushSignalCenter messageFromUserInfo:payload
                                             fallbackId:@"launch"
                                                  title:nil
                                                   body:nil]];
}

- (NSString *)currentEnvironment {
  NSString *environment = [PushSignalCenter apsEnvironment];
  if (environment != nil) {
    return environment;
  }
#if DEBUG
  return @"sandbox";
#else
  return @"production";
#endif
}

+ (nullable NSString *)apsEnvironment {
  NSURL *url = [[NSBundle mainBundle] URLForResource:@"embedded" withExtension:@"mobileprovision"];
  if (url == nil) {
    return nil;
  }
  NSData *data = [NSData dataWithContentsOfURL:url];
  if (data == nil) {
    return nil;
  }
  NSString *contents = [[NSString alloc] initWithData:data encoding:NSASCIIStringEncoding];
  if (contents == nil) {
    return nil;
  }

  NSRegularExpression *regex =
      [NSRegularExpression regularExpressionWithPattern:@"<key>aps-environment</key>\\s*<string>(\\w+)</string>"
                                                options:0
                                                  error:nil];
  NSTextCheckingResult *match =
      [regex firstMatchInString:contents options:0 range:NSMakeRange(0, contents.length)];
  if (match == nil || match.numberOfRanges < 2) {
    return nil;
  }

  NSString *value = [contents substringWithRange:[match rangeAtIndex:1]];
  if ([value isEqualToString:@"development"]) {
    return @"sandbox";
  }
  if ([value isEqualToString:@"production"]) {
    return @"production";
  }
  return nil;
}

+ (NSDictionary *)messageFromNotification:(UNNotification *)notification {
  UNNotificationContent *content = notification.request.content;
  return [self messageFromUserInfo:content.userInfo
                        fallbackId:notification.request.identifier
                             title:content.title.length > 0 ? content.title : nil
                              body:content.body.length > 0 ? content.body : nil];
}

+ (NSDictionary *)messageFromUserInfo:(NSDictionary *)userInfo
                           fallbackId:(NSString *)fallbackId
                                title:(NSString *)title
                                 body:(NSString *)body {
  id aps = userInfo[@"aps"];
  id alert = [aps isKindOfClass:[NSDictionary class]] ? aps[@"alert"] : nil;
  NSString *resolvedTitle = title;
  NSString *resolvedBody = body;

  if ([alert isKindOfClass:[NSString class]]) {
    resolvedBody = resolvedBody ?: (NSString *)alert;
  } else if ([alert isKindOfClass:[NSDictionary class]]) {
    resolvedTitle = resolvedTitle ?: [self stringify:alert[@"title"]];
    resolvedBody = resolvedBody ?: [self stringify:alert[@"body"]];
  }

  NSMutableDictionary *data = [NSMutableDictionary dictionary];
  [userInfo enumerateKeysAndObjectsUsingBlock:^(id key, id value, BOOL *stop) {
    NSString *stringKey = [NSString stringWithFormat:@"%@", key];
    if ([stringKey isEqualToString:@"aps"]) {
      return;
    }
    NSString *stringValue = [self stringify:value];
    if (stringValue != nil) {
      data[stringKey] = stringValue;
    }
  }];

  NSMutableDictionary *message = [NSMutableDictionary dictionary];
  NSString *messageId = [self stringify:userInfo[@"gcm.message_id"]] ?: fallbackId;
  if (messageId != nil) {
    message[@"id"] = messageId;
  }
  if (resolvedTitle != nil) {
    message[@"title"] = resolvedTitle;
  }
  if (resolvedBody != nil) {
    message[@"body"] = resolvedBody;
  }
  message[@"data"] = data;
  return message;
}

+ (nullable NSString *)stringify:(id)value {
  if (value == nil || value == [NSNull null]) {
    return nil;
  }
  if ([value isKindOfClass:[NSString class]]) {
    return (NSString *)value;
  }
  if ([value isKindOfClass:[NSNumber class]]) {
    return [(NSNumber *)value stringValue];
  }
  if ([NSJSONSerialization isValidJSONObject:value]) {
    NSData *jsonData = [NSJSONSerialization dataWithJSONObject:value options:0 error:nil];
    if (jsonData != nil) {
      return [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
    }
  }
  return [NSString stringWithFormat:@"%@", value];
}

#pragma mark - Swizzling

- (void)swizzleAppDelegate {
  if (_didSwizzleAppDelegate) {
    return;
  }
  id appDelegate = [UIApplication sharedApplication].delegate;
  if (appDelegate == nil) {
    return;
  }
  _didSwizzleAppDelegate = YES;

  Class target = object_getClass(appDelegate);
  Class source = [PushSignalAppDelegateHook class];

  [self swizzleTarget:target
             original:@selector(application:didRegisterForRemoteNotificationsWithDeviceToken:)
          replacement:@selector(pushSignal_application:didRegisterForRemoteNotificationsWithDeviceToken:)
               source:source];
  [self swizzleTarget:target
             original:@selector(application:didFailToRegisterForRemoteNotificationsWithError:)
          replacement:@selector(pushSignal_application:didFailToRegisterForRemoteNotificationsWithError:)
               source:source];
}

- (void)swizzleNotificationCenterDelegateIfNeeded {
  if (_didSwizzleNotificationCenter) {
    return;
  }
  _didSwizzleNotificationCenter = YES;

  Class target = [UNUserNotificationCenter class];
  SEL original = @selector(setDelegate:);
  SEL replacement = @selector(pushSignal_setDelegate:);

  Method originalMethod = class_getInstanceMethod(target, original);
  Method replacementMethod = class_getInstanceMethod(target, replacement);
  if (originalMethod == NULL || replacementMethod == NULL) {
    return;
  }
  method_exchangeImplementations(originalMethod, replacementMethod);
}

- (void)swizzleTarget:(Class)target
             original:(SEL)original
          replacement:(SEL)replacement
               source:(Class)source {
  Method replacementMethod = class_getInstanceMethod(source, replacement);
  if (replacementMethod == NULL) {
    return;
  }

  IMP replacementImp = method_getImplementation(replacementMethod);
  const char *types = method_getTypeEncoding(replacementMethod);
  Class superClass = class_getSuperclass(target);
  Method superMethod = superClass != Nil ? class_getInstanceMethod(superClass, original) : NULL;

  if (class_addMethod(target, original, replacementImp, types)) {
    if (superMethod != NULL) {
      class_addMethod(
          target, replacement, method_getImplementation(superMethod), method_getTypeEncoding(superMethod));
    }
    return;
  }

  if (!class_addMethod(target, replacement, replacementImp, types)) {
    return;
  }
  Method originalMethod = class_getInstanceMethod(target, original);
  Method addedReplacement = class_getInstanceMethod(target, replacement);
  if (originalMethod == NULL || addedReplacement == NULL) {
    return;
  }
  method_exchangeImplementations(originalMethod, addedReplacement);
}

@end

@implementation PushSignalAppDelegateHook

- (void)pushSignal_application:(UIApplication *)application
    didRegisterForRemoteNotificationsWithDeviceToken:(NSData *)deviceToken {
  [[PushSignalCenter shared] handleDeviceToken:deviceToken];
  [self pushSignal_forwardDeviceToken:application deviceToken:deviceToken];
}

- (void)pushSignal_application:(UIApplication *)application
    didFailToRegisterForRemoteNotificationsWithError:(NSError *)error {
  [[PushSignalCenter shared] handleRegistrationError:error];
  [self pushSignal_forwardError:application error:error];
}

- (void)pushSignal_forwardDeviceToken:(UIApplication *)application deviceToken:(NSData *)deviceToken {
  SEL hooked = @selector(application:didRegisterForRemoteNotificationsWithDeviceToken:);
  SEL original = @selector(pushSignal_application:didRegisterForRemoteNotificationsWithDeviceToken:);
  if (![self respondsToSelector:original]) {
    return;
  }
  Class cls = object_getClass(self);
  IMP originalImp = class_getMethodImplementation(cls, original);
  IMP hookedImp = class_getMethodImplementation(cls, hooked);
  if (originalImp == NULL || originalImp == hookedImp) {
    return;
  }
  void (*fn)(id, SEL, UIApplication *, NSData *) = (void (*)(id, SEL, UIApplication *, NSData *))originalImp;
  fn(self, original, application, deviceToken);
}

- (void)pushSignal_forwardError:(UIApplication *)application error:(NSError *)error {
  SEL hooked = @selector(application:didFailToRegisterForRemoteNotificationsWithError:);
  SEL original = @selector(pushSignal_application:didFailToRegisterForRemoteNotificationsWithError:);
  if (![self respondsToSelector:original]) {
    return;
  }
  Class cls = object_getClass(self);
  IMP originalImp = class_getMethodImplementation(cls, original);
  IMP hookedImp = class_getMethodImplementation(cls, hooked);
  if (originalImp == NULL || originalImp == hookedImp) {
    return;
  }
  void (*fn)(id, SEL, UIApplication *, NSError *) = (void (*)(id, SEL, UIApplication *, NSError *))originalImp;
  fn(self, original, application, error);
}

@end

@implementation UNUserNotificationCenter (PushSignal)

- (void)pushSignal_setDelegate:(id<UNUserNotificationCenterDelegate>)delegate {
  if (delegate != nil && [delegate isKindOfClass:[PushSignalCenter class]]) {
    [self pushSignal_setDelegate:delegate];
    return;
  }

  [[PushSignalCenter shared] rememberForwardingDelegate:delegate];
  [self pushSignal_setDelegate:[PushSignalCenter shared]];
}

@end
