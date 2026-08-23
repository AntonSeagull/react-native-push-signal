#import "PushSignalLaunchStore.h"
#import <UIKit/UIKit.h>

static NSDictionary *sLaunchOptions;

NSDictionary *PushSignalCopyLaunchOptions(void) {
  return sLaunchOptions;
}

@implementation PushSignalLaunchStore

+ (void)load {
  [[NSNotificationCenter defaultCenter]
      addObserverForName:UIApplicationDidFinishLaunchingNotification
                  object:nil
                   queue:nil
              usingBlock:^(NSNotification *notification) {
                sLaunchOptions = [notification.userInfo copy];
              }];
}

+ (NSDictionary *)launchOptions {
  return sLaunchOptions;
}

@end
