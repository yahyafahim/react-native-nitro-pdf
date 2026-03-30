require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "NitroPdf"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported, :visionos => 1.0 }
  s.source       = { :git => "https://github.com/yahyafahim/react-native-nitro-pdf.git", :tag => "#{s.version}" }

  s.source_files = [
    # Implementation (Swift)
    "ios/**/*.{swift}",
    # Autolinking/Registration (Objective-C++)
    "ios/**/*.{m,mm}",
    # Implementation (C++ objects)
    "cpp/**/*.{hpp,cpp}",
  ]

  load 'nitrogen/generated/ios/NitroPdf+autolinking.rb'
  add_nitrogen_files(s)

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'
  # Explicitly depend on RCT-Folly so CocoaPods wires its headers for
  # transitive JSI/NitroModules builds in use_frameworks projects.
  s.dependency 'RCT-Folly'

  # Some React Native + use_frameworks setups expose folly headers via
  # private/public Pods header paths instead of framework headers.
  # Adding both paths avoids "folly/folly-config.h not found" when NitroPdf
  # is consumed as a module.
  s.user_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => '$(inherited) "${PODS_ROOT}/Headers/Private/RCT-Folly" "${PODS_ROOT}/Headers/Public/RCT-Folly"'
  }
  install_modules_dependencies(s)
end
