# Task Proposal: Fix Large Model File Detection on iOS

## Summary
The current implementation of `isModelDownloaded` in `ios/ExpoLlmMediapipeModule.swift` determines whether a downloaded model exists by checking that the file size is greater than zero. The file size is retrieved from `FileManager` and coerced using `NSNumber.intValue`. Because `intValue` is a 32-bit signed integer, model files larger than 2 GB (2,147,483,647 bytes) can overflow and produce negative values, causing the method to incorrectly report that the model is not downloaded.

## Impact
When a model larger than 2 GB is downloaded, `isModelDownloaded` may return `false` even though the file is present. This breaks the download flow for large models: the JavaScript layer believes the model is missing, may try to re-download it, or fail to load it entirely.

## Proposed Fix
Update `isModelDownloaded` (and any related size checks) to read file sizes using 64-bit-safe APIs. Specifically:

- Replace the `NSNumber.intValue` cast with `int64Value` (or bridge to Swift's `Int`/`UInt64`) before performing the size comparison.
- Add a unit test (if possible) that simulates a file attribute larger than 2 GB to ensure the logic handles large sizes correctly.

This change will ensure that the module accurately recognizes downloaded models regardless of their size.
