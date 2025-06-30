#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html
#
Pod::Spec.new do |s|
  s.name             = 'health'
  s.version          = '1.0.4'
  s.summary          = 'Wrapper for Apple\'s HealthKit on iOS and Google\'s Health Connect on Android.'
  s.description      = <<-DESC
Wrapper for Apple's HealthKit on iOS and Google's Health Connect on Android.
                       DESC
  s.homepage         = 'https://pub.dev/packages/health'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Copenhagen Center for Health Technology' => 'cph.cachet@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.public_header_files = 'Classes/**/*.h'
  s.dependency 'Flutter'

  s.ios.deployment_target = '13.0'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'VALID_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
end

