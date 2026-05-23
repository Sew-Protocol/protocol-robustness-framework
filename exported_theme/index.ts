/**
 * Theme System Public API
 * 
 * Provides a clean, composable interface for theming
 * Inspired by wagmi's modular architecture
 * 
 * @example
 * ```typescript
 * // Basic usage
 * import { getTextInputTheme } from '@/utils/theme';
 * 
 * // Composable usage
 * import { getTextInputTheme, withErrorState, compose } from '@/utils/theme';
 * 
 * // Reactive hooks
 * import { useTextInputTheme, useThemeSystem } from '@/utils/theme';
 * ```
 */

// Core utilities
export * from './core/colors';
export * from './core/spacing';
export * from './core/typography';
export * from './core/types';
export * from './core/compose';
export * from './core/tokens';

// Component themes
export * from './components/text-input';
export * from './components/button';
export * from './components/button-styles';
export * from './components/card';
export * from './components/dialog';

// Layout utilities
export * from './layout';

// Hooks
export * from './hooks/useThemeSystem';
export * from './hooks/useThemeComposer';

// Legacy hooks (deprecated - use composer-based hooks below instead)
// Note: These are exported for backward compatibility but will be removed in a future version
export * from './hooks/useComponentTheme';

// Convenience hooks (composer-based - recommended)
// These hooks use the theme composer system for consistent theming
export { useFormInputTheme } from '../../hooks/useFormInputTheme';
export { useButtonTheme } from '../../hooks/useButtonTheme';
export { useCardTheme } from '../../hooks/useCardTheme';
export { useDialogTheme } from '../../hooks/useDialogTheme';

// Composer
export * from './composer';

