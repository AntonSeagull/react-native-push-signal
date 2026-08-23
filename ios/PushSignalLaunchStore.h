#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

#ifdef __cplusplus
extern "C" {
#endif

NSDictionary * _Nullable PushSignalCopyLaunchOptions(void);

#ifdef __cplusplus
}
#endif

@interface PushSignalLaunchStore : NSObject

+ (nullable NSDictionary *)launchOptions;

@end

NS_ASSUME_NONNULL_END
