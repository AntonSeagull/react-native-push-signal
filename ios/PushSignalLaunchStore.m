#import "PushSignalLaunchStore.h"
#import <UIKit/UIKit.h>

static NSDictionary *sLaunchOptions;

NSDictionary *PushSignalCopyLaunchOptions(void) {
  return sLaunchOptions;
}

@implementation PushSignalLaunchStore

+ (void)load {
  [[NSNotificationCenter defaultCenter]
      addObserverForName:UIApplicationWillFinishLaunchingNotification
                  object:nil
                   queue:nil
              usingBlock:^(NSNotification *notification) {
                Class center = NSClassFromString(@"PushSignalCenter");
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
                Class center = NSClassFromString(@"PushSignalCenter");
                if ([center respondsToSelector:@selector(bootstrapWithLaunchOptions:)]) {
                  [center bootstrapWithLaunchOptions:sLaunchOptions];
                }
              }];
}

+ (NSDictionary *)launchOptions {
  return sLaunchOptions;
}

@end
