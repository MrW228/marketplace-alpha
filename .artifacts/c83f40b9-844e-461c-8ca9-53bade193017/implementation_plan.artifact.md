# Fix Navigation Synchronization and Settings Interaction

This plan addresses the synchronization issues between the bottom navigation bar and the horizontal pager, enables swipe gestures, and fixes interaction bugs in the settings screen.

## Proposed Changes

### [Component] Navigation & State Management

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Windows/Downloads/AyuGram%20Desktop/marketplacee2/marketplace2/app/src/main/java/com/example/marketplace/MainActivity.kt)

- **Sync Logic**: Update the `NavigationBar` to correctly retrieve the `pager_index` from the `main_tabs` backstack entry, ensuring the correct icon is highlighted even when on sub-screens like Settings.
- **Loop Prevention**: Refine the `MainTabsScreen` to use `LaunchedEffect` with checks to prevent infinite loops between pager state and navigation state.
- **Swipe Support**: Enable `userScrollEnabled = true` in the `HorizontalPager`.
- **Callback Updates**: Update `onNavigateToCart` and other callbacks to trigger tab switching instead of full navigation when appropriate.
- **Settings Interaction**: Verify and ensure `Surface` `onClick` handlers are robust for theme and color selection.

### [Component] Assets & Build

- **APK Icon**: Ensure the custom icon assets are fully integrated (already done in previous step, but will verify during build).
- **APK Build**: Trigger a fresh build to verify all fixes.

## Verification Plan

### Automated Tests
- Run `:app:assembleDebug` to ensure the project compiles and the APK is generated.

### Manual Verification
- **Bottom Nav**: Click each tab and verify the pager scrolls smoothly with animations.
- **Swipe**: Swipe between pages and verify the bottom navigation icon updates in real-time.
- **Settings**: Open Settings, change theme/color, and verify it updates immediately.
- **Navigation from Settings**: While in Settings, click "Home" in the bottom nav and verify it returns to the home tab.
- **Back Button**: Test "back" action in Cart tab to ensure it returns to Home tab if specified.
