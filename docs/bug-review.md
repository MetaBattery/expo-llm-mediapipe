# Bug Review

This document lists three issues identified during a static review of the repository.
Each entry includes the observed problem, why it is a bug, and a suggested follow-up
implementation task to address it.

## 1. `ModelManager.downloadModel` misclassifies `ERR_ALREADY_DOWNLOADING`
- **Location:** `src/ModelManager.ts`, lines 164-187.
- **Problem:** Native layers reject duplicate download requests with the
  `ERR_ALREADY_DOWNLOADING` code. The catch block treats every non-cancel rejection as a
  fatal error, forcing the model status to `"error"` even though the original download is
  still running successfully. This incorrect state transition can interrupt UIs that react
  to error status and may trigger unnecessary retry or cleanup flows.
- **Suggested fix task:** Inspect the rejection code and, when it is
  `ERR_ALREADY_DOWNLOADING`, keep the existing `"downloading"` status (and avoid throwing)
  so that the manager reflects the in-progress download.

## 2. `ModelManager.deleteModel` fails to reset state when native delete resolves `false`
- **Location:** `src/ModelManager.ts`, lines 204-216.
- **Problem:** Both the iOS and Android implementations return `false` when the target
  file does not exist. In that case the manager leaves the in-memory status untouched,
  meaning a previously marked `"downloaded"` model continues to appear available even
  though the file is missing. Subsequent `loadModel` calls then fail unexpectedly.
- **Suggested fix task:** Treat a `false` return from `deleteDownloadedModel` as an
  indicator that no file remains and proactively reset the model's status to
  `"not_downloaded"`, clearing any cached progress/error values.

## 3. `generateStreamingText` request IDs can collide with hook-driven requests
- **Location:** `src/ExpoLlmMediapipeModule.ts`, lines 643-738.
- **Problem:** The standalone `generateStreamingText` helper assigns a random request ID.
  The `useLLM` hook, however, uses a deterministic counter starting at `0`. Because both
  code paths feed the same native event channel, a random ID can collide with an existing
  in-flight hook request, causing partial/error events to be delivered to the wrong
  consumer. This manifests as missing responses or unexpected errors in whichever caller
  loses the race.
- **Suggested fix task:** Replace the random ID with a shared monotonic counter (or a
  module-level generator) that guarantees uniqueness across all request sources.
