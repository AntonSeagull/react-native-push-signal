#import "NotificationService.h"

/**
 * Adds rich content from the push payload before the notification is shown:
 *
 * - an image from the `image` key (downloaded and attached as
 *   `UNNotificationAttachment`);
 * - action buttons from the `buttons` key (a JSON array of
 *   `{ "id": ..., "title": ..., ... }`), registered as a dynamic
 *   `UNNotificationCategory` and selected via `categoryIdentifier`.
 *
 * Requires the server to set `aps.mutable-content = 1` for notifications that
 * carry an image or buttons. The action identifiers use the `ps:` prefix so the
 * main app can tell a button tap apart from the plain tap on the body.
 */

static NSString *const PushSignalActionPrefix = @"ps:";

@interface NotificationService ()

@property (nonatomic, copy) void (^contentHandler)(UNNotificationContent *contentToDeliver);
@property (nonatomic, strong) UNMutableNotificationContent *bestAttemptContent;

@end

@implementation NotificationService

- (void)didReceiveNotificationRequest:(UNNotificationRequest *)request
                   withContentHandler:(void (^)(UNNotificationContent *))contentHandler {
  self.contentHandler = contentHandler;
  self.bestAttemptContent = [request.content mutableCopy];

  UNMutableNotificationContent *content = self.bestAttemptContent;
  NSDictionary *userInfo = request.content.userInfo;

  NSString *imageURL = [self imageURLFromUserInfo:userInfo];
  NSArray<NSDictionary *> *buttons = [self buttonsFromUserInfo:userInfo];

  if (imageURL == nil && buttons.count == 0) {
    contentHandler(content);
    return;
  }

  if (buttons.count > 0) {
    NSString *categoryId = [self installCategoryWithButtons:buttons];
    if (categoryId != nil) {
      content.categoryIdentifier = categoryId;
    }
  }

  if (imageURL == nil) {
    contentHandler(content);
    return;
  }

  [self attachImageAtURL:imageURL
              completion:^(BOOL attached) {
    contentHandler(content);
  }];
}

- (void)serviceExtensionTimeWillExpire {
  if (self.contentHandler != nil) {
    self.contentHandler(self.bestAttemptContent);
  }
}

#pragma mark - Payload

- (nullable NSString *)imageURLFromUserInfo:(NSDictionary *)userInfo {
  id raw = userInfo[@"image"];
  if (![raw isKindOfClass:[NSString class]]) {
    return nil;
  }
  NSString *url = [(NSString *)raw stringByTrimmingCharactersInSet:
                                     [NSCharacterSet whitespaceAndNewlineCharacterSet]];
  return url.length > 0 ? url : nil;
}

- (NSArray<NSDictionary *> *)buttonsFromUserInfo:(NSDictionary *)userInfo {
  id raw = userInfo[@"buttons"];
  if ([raw isKindOfClass:[NSArray class]]) {
    return [self sanitizeButtons:raw];
  }
  if (![raw isKindOfClass:[NSString class]]) {
    return @[];
  }
  NSData *data = [(NSString *)raw dataUsingEncoding:NSUTF8StringEncoding];
  if (data == nil) {
    return @[];
  }
  id parsed = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
  return [parsed isKindOfClass:[NSArray class]] ? [self sanitizeButtons:parsed] : @[];
}

- (NSArray<NSDictionary *> *)sanitizeButtons:(NSArray *)raw {
  NSMutableArray<NSDictionary *> *buttons = [NSMutableArray array];
  for (id item in raw) {
    if (![item isKindOfClass:[NSDictionary class]]) {
      continue;
    }
    NSDictionary *button = (NSDictionary *)item;
    id idValue = button[@"id"];
    id titleValue = button[@"title"];
    if (![idValue isKindOfClass:[NSString class]] || ![titleValue isKindOfClass:[NSString class]]) {
      continue;
    }
    if ([(NSString *)idValue length] == 0 || [(NSString *)titleValue length] == 0) {
      continue;
    }
    [buttons addObject:button];
  }
  return buttons;
}

#pragma mark - Buttons

- (nullable NSString *)installCategoryWithButtons:(NSArray<NSDictionary *> *)buttons {
  NSMutableArray<UNNotificationAction *> *actions = [NSMutableArray array];
  for (NSDictionary *button in buttons) {
    NSString *buttonId = button[@"id"];
    NSString *title = button[@"title"];
    UNNotificationAction *action = [UNNotificationAction
        actionWithIdentifier:[PushSignalActionPrefix stringByAppendingString:buttonId]
                       title:title
                     options:UNNotificationActionOptionForeground];
    [actions addObject:action];
  }

  if (actions.count == 0) {
    return nil;
  }

  NSString *categoryId =
      [@"ps_" stringByAppendingString:[[NSUUID UUID] UUIDString]];
  UNNotificationCategory *category = [UNNotificationCategory
      categoryWithIdentifier:categoryId
                     actions:actions
           intentIdentifiers:@[]
                     options:UNNotificationCategoryOptionNone];

  [[UNUserNotificationCenter currentNotificationCenter]
      setNotificationCategories:[NSSet setWithObject:category]];
  return categoryId;
}

#pragma mark - Image

- (void)attachImageAtURL:(NSString *)urlString completion:(void (^)(BOOL))completion {
  NSURL *url = [NSURL URLWithString:urlString];
  if (url == nil) {
    completion(NO);
    return;
  }

  NSURLSessionConfiguration *config = [NSURLSessionConfiguration defaultSessionConfiguration];
  config.timeoutIntervalForRequest = 20;
  config.timeoutIntervalForResource = 25;
  NSURLSession *session = [NSURLSession sessionWithConfiguration:config];

  [[session dataTaskWithURL:url
          completionHandler:^(NSData *_Nullable data, NSURLResponse *_Nullable response,
                              NSError *_Nullable error) {
            NSURL *fileURL = nil;
            if (data.length > 0 && error == nil) {
              fileURL = [self writeImageData:data response:response];
            }
            if (fileURL != nil) {
              [self attachFile:fileURL];
            }
            completion(fileURL != nil);
          }] resume];
}

- (nullable NSURL *)writeImageData:(NSData *)data response:(NSURLResponse *)response {
  NSString *extension = [self fileExtensionForResponse:response];
  NSString *fileName = [@"pushsignal-image." stringByAppendingString:extension ?: @"jpg"];
  NSURL *directory = [NSURL fileURLWithPath:NSTemporaryDirectory()];
  NSURL *fileURL = [directory URLByAppendingPathComponent:fileName];
  if (![data writeToURL:fileURL atomically:YES]) {
    return nil;
  }
  return fileURL;
}

- (NSString *)fileExtensionForResponse:(NSURLResponse *)response {
  NSString *mime = response.MIMEType;
  if ([mime isEqualToString:@"image/png"]) {
    return @"png";
  }
  if ([mime isEqualToString:@"image/gif"]) {
    return @"gif";
  }
  if ([mime isEqualToString:@"image/webp"]) {
    return @"webp";
  }
  return @"jpg";
}

- (void)attachFile:(NSURL *)fileURL {
  NSError *error = nil;
  UNNotificationAttachment *attachment = [UNNotificationAttachment
      attachmentWithIdentifier:@"image"
                           URL:fileURL
                       options:nil
                         error:&error];
  if (attachment != nil) {
    self.bestAttemptContent.attachments = @[ attachment ];
  }
}

@end
