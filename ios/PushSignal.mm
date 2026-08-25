#import "PushSignal.h"
#import "PushSignalCenter.h"

@implementation PushSignal {
  BOOL _listening;
}

- (void)initialize:(NSDictionary *)config
           resolve:(RCTPromiseResolveBlock)resolve
            reject:(RCTPromiseRejectBlock)reject {
  (void)config;
  resolve(nil);
}

- (void)getCredentials:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  [[PushSignalCenter shared] fetchCredentialsWithResolver:^(NSDictionary *credentials) {
    resolve(credentials);
  }
                                                 rejecter:^(NSError *error) {
                                                   reject(@"E_CREDENTIALS", error.localizedDescription, error);
                                                 }];
}

- (void)startListening {
  if (_listening) {
    return;
  }
  _listening = YES;

  __weak PushSignal *weakSelf = self;
  [[PushSignalCenter shared] setOnMessage:^(NSDictionary *message) {
    PushSignal *strongSelf = weakSelf;
    if (strongSelf == nil) {
      return;
    }
    [strongSelf emitOnMessage:message];
  }];
  [[PushSignalCenter shared] setOnNotificationPress:^(NSDictionary *message) {
    PushSignal *strongSelf = weakSelf;
    if (strongSelf == nil) {
      return;
    }
    [strongSelf emitOnNotificationPress:message];
  }];
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::NativePushSignalSpecJSI>(params);
}

+ (NSString *)moduleName {
  return @"PushSignal";
}

@end
