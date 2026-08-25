#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>

NS_ASSUME_NONNULL_BEGIN

@interface PushSignalCenter : NSObject <UNUserNotificationCenterDelegate>

+ (instancetype)shared;
+ (void)installEarly;
+ (void)bootstrapWithLaunchOptions:(nullable NSDictionary *)launchOptions;

- (void)install;
- (void)rememberForwardingDelegate:(nullable id<UNUserNotificationCenterDelegate>)delegate;

- (void)fetchCredentialsWithResolver:(void (^)(NSDictionary *credentials))resolve
                            rejecter:(void (^)(NSError *error))reject;

- (void)setOnMessage:(void (^)(NSDictionary *message))callback;
- (void)setOnNotificationPress:(void (^)(NSDictionary *message))callback;

- (void)handleDeviceToken:(NSData *)deviceToken;
- (void)handleRegistrationError:(NSError *)error;

@end

NS_ASSUME_NONNULL_END
