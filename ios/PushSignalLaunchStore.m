#import "PushSignalLaunchStore.h"
#import "PushSignalCenter.h"
#import <UIKit/UIKit.h>

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
                [PushSignalCenter installEarly];
              }];

  [[NSNotificationCenter defaultCenter]
      addObserverForName:UIApplicationDidFinishLaunchingNotification
                  object:nil
                   queue:nil
              usingBlock:^(NSNotification *notification) {
                sLaunchOptions = [notification.userInfo copy];
                [PushSignalCenter bootstrapWithLaunchOptions:sLaunchOptions];
              }];
}

+ (NSDictionary *)launchOptions {
  return sLaunchOptions;
}

@end
