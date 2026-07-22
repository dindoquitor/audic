# Changelog

## 1.0.4

- Set default music streaming cache to unlimited (was 1GB)
- Set default image cache to 4GB (was 512MB)
- Fresh installs get the new limits; existing users keep their current settings

## 1.0.3

- Fixed update notification link returning 404
- Fixed in-app update downloading wrong APK variant
- Improved APK selection to match device architecture and build flavor

## 1.0.2

- Replace OPUS audio quality with HIGH and AUTO adaptive modes
- Add network-aware adaptive bitrate selection for AUTO quality
- Dynamic audio quality selector in settings and player UI
- Fix release APK naming to Audic-<version>-<variant>.apk
- Fix compiler warnings and deprecated API usage

## 1.0.1

- Initial release
- Fix update setting
- Fix music playback errors
- Remove legacy icon support and clean up unused resources
