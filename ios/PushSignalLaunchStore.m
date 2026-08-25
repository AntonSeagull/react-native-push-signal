#import "PushSignalLaunchStore.h"
#import <UIKit/UIKit.h>

// Swift `@objc(PushSignalCenter)` — declared so messaging via `id` type-checks.
@interface PushSignalCenter : NSObject
+ (void)installEarly;
+ (void)bootstrapWithLaunchOptions:(nullable NSDictionary *)launchOptions;
@end

static NSDictionary *sLaunchOptions;

NSDictionary *PushSignalCopyLaunchOptions(void) {
  return sLaunchOptions;
}

@implementation PushSignalLaunchStore

+ (void)load {
  // String form: `UIApplicationWillFinishLaunchingNotification` is not declared in
  // every UIKit header set, but the system still posts this name.
  [[NSNotificationCenter defaultCenter]
      addObserverForName:@"UIApplicationWillFinishLaunchingNotification"
                  object:nil
                   queue:nil
              usingBlock:^(NSNotification *notification) {
                id center = NSClassFromString(@"PushSignalCenter");
                if ([center respondsToSelector:@selector(installEarly)]) {
                  [center installEarly];
                }
              }];

  [[NSNotificationCenter defaultCenter]
      addObserverForName:UIApplicationDidFinishLaunchingNotification
                  object:nil
                   queue:nil
              usingBlock:^(NSNotification *notification) {
                sLaunchOptions = [notification.userInfo copy];
                id center = NSClassFromString(@"PushSignalCenter");
                if ([center respondsToSelector:@selector(bootstrapWithLaunchOptions:)]) {
                  [center bootstrapWithLaunchOptions:sLaunchOptions];
                }
              }];
}

+ (NSDictionary *)launchOptions {
  return sLaunchOptions;
}

@end
