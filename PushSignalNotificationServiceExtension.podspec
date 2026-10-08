require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "PushSignalNotificationServiceExtension"
  s.version      = package["version"]
  s.summary      = "Notification service extension for react-native-push-signal (rich images and action buttons)"
  s.description  = "Adds UNNotificationAttachment (image) and dynamic action buttons to notifications delivered by react-native-push-signal."
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported }
  s.source       = { :git => "https://github.com/AntonSeagull/react-native-push-signal.git", :tag => "#{s.version}" }

  s.source_files = "ios/NotificationServiceExtension/*.{h,m}"
  s.private_header_files = "ios/NotificationServiceExtension/*.h"
  s.frameworks = "UserNotifications"
end
