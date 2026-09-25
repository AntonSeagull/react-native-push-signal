#import "PushSignal.h"
#import "PushSignalCenter.h"
#import <UIKit/UIKit.h>

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

- (void)getDiagnostics:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  (void)reject;
  UIDevice *device = [UIDevice currentDevice];
  resolve(@{
    @"platform": @"ios",
    @"gmsAvailable": @YES,
    @"gmsStatus": @0,
    @"manufacturer": @"Apple",
    @"brand": @"Apple",
    @"model": device.model ?: @"iOS device",
    @"provider": @"apns",
    @"providerName": @"Apple Push Notification service (APNs)",
    @"providerInstalled": @YES,
  });
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
