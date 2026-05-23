/**
 * Dialog theme primitives
 * 
 * Provides theming utilities for Dialog components
 */

import type { MD3Theme } from 'react-native-paper';
import { getBackgroundColor } from '../core/colors';
import type { BackgroundVariant, ThemeComposer, BaseTheme } from '../core/types';

/**
 * Dialog theme configuration
 */
export interface DialogThemeConfig {
  variant?: BackgroundVariant;
}

/**
 * Dialog theme type (simplified for now)
 */
export interface DialogTheme extends BaseTheme {
  backgroundColor?: string;
}

/**
 * Base Dialog theme
 * 
 * @param theme - MD3Theme instance
 * @param config - Dialog theme configuration
 * @returns Dialog theme object
 */
export function getDialogTheme(
  theme: MD3Theme,
  config: DialogThemeConfig = {}
): DialogTheme {
  const variant = config.variant || 'surface';

  return {
    theme,
    backgroundColor: getBackgroundColor(theme, variant),
  };
}

