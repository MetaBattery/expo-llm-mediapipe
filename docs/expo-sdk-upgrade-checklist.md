# Expo SDK 52 → 54 module upgrade checklist

The Expo 53 and 54 releases introduce React Native upgrades (0.75 → 0.76), updated lint/test tooling, and refreshed Expo module build scripts. This checklist summarizes the concrete code and configuration updates needed when migrating the `expo-llm-mediapipe` module across SDK 52 → 53 → 54.

## SDK 52 → 53

- **Runtime targets**
  - Align the development toolchain with Expo 53 by bumping `expo` to `~53.x` and `react-native` to the matching 0.75 release. Verify Android `compileSdkVersion`/`targetSdkVersion` ≥ 34 so the module builds against React Native 0.75 defaults. The existing Gradle file already uses API 34, so no Android changes were required during this pass.
- **Module scripts**
  - Stay on `expo-module-scripts@4` when first moving to SDK 53. The CLI behaviour is unchanged from SDK 52, so no project-specific adjustments were needed beyond installing the newer Expo/React Native versions and rerunning `npm install`.
- **Regression checks**
  - Run `npm run lint`, `npm run build`, and `npm run test` after the dependency bumps to confirm the module still compiles and its TypeScript types emit correctly under the React Native 0.75 toolchain.

## SDK 53 → 54

- **Development dependencies**
  - Upgrade `expo` to `~54.0.0`, `react-native` to `0.76.x`, and `expo-module-scripts` to `^5.0.7`. These align the module with Expo 54's React Native 0.76 toolchain and Hermes 2024.06 runtime.
  - Keep `@types/react` on the React 18.3 channel (`~18.3.12`) to match Expo 54's React version.
- **Prepare script compatibility**
  - `expo-module-scripts@5` shells out through Yarn or pnpm when the `npm_config_user_agent` contains their tokens. Explicitly preserve the original user agent inside `scripts/prepare.js` so the prepare step continues to execute through npm without pulling in Yarn workspaces.
- **Linting configuration**
  - Expo 54 adopts ESLint 9 and the flat config format. Replace `.eslintrc.js` with a new `eslint.config.js` that composes `expo-module-scripts/eslint.config.base` and adds project-specific ignores (e.g., the compiled `build/` output).
  - Run `npm run lint -- --fix` once after the dependency upgrade to sync formatting with the newer Prettier + ESLint bundle.
- **Jest configuration**
  - Add a `jest.config.js` that re-exports `expo-module-scripts/jest-preset` and sets `passWithNoTests: true` so `npm run test` succeeds even when the package contains no test files.
- **Hook hygiene**
  - React Native 0.76 ships with stricter hook linting. Update `useLLM` so it guards the initial branch selection with a `useRef` and throws if callers switch between downloadable and bundled model props at runtime. This keeps hook ordering stable while retaining the existing overload signature.
- **Verification**
  - Re-run `npm run lint`, `npm run build`, and `npm run test` after the Expo 54 bump to ensure the TypeScript, bundler output, and Jest runner all succeed without manual intervention.
