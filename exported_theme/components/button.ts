/**
 * Button theme primitives
 * 
 * Follows same pattern as TextInput for consistency
 */

import type { MD3Theme } from 'react-native-paper';
import { getColorByVariant, getBackgroundColor } from '../core/colors';
import type { ColorVariant, ComponentSize, ThemeComposer, BaseTheme } from '../core/types';

/**
 * Button theme type
 * Compatible with react-native-paper's Button theme prop
 */
export interface ButtonTheme extends BaseTheme {
  colors: {
    primary?: string;
    onPrimary?: string;
    secondary?: string;
    onSecondary?: string;
  };
}

/**
 * Button theme configuration
 */
export interface ButtonThemeConfig {
  variant?: 'contained' | 'outlined' | 'text';
  color?: ColorVariant;
  size?: ComponentSize;
  disabled?: boolean;
}

/**
 * Base Button theme
 * 
 * @param theme - MD3Theme instance
 * @param config - Button theme configuration
 * @returns Button theme object
 */
export function getButtonTheme(
  theme: MD3Theme,
  config: ButtonThemeConfig = {}
): ButtonTheme {
  const variant = config.variant || 'contained';
  const color = config.color || 'primary';
  const isDisabled = config.disabled || false;

  const baseTheme: ButtonTheme = {
    theme,
    colors: {
      primary: getColorByVariant(theme, color),
    },
  };

  // Add disabled state
  if (isDisabled) {
    baseTheme.colors.primary = theme.colors.onSurfaceDisabled;
  }

  return baseTheme;
}

/**
 * Button theme with error variant
 * 
 * Composable: Can be combined with other modifiers
 */
export function withErrorVariant(theme: MD3Theme): ThemeComposer<ButtonTheme> {
  return (baseTheme: ButtonTheme) => ({
    ...baseTheme,
    colors: {
      ...baseTheme.colors,
      primary: getColorByVariant(theme, 'error'),
    },
  });
}

/**
 * Button theme with disabled state
 * 
 * Composable: Can be combined with other modifiers
 */
export function withButtonDisabledState(theme: MD3Theme): ThemeComposer<ButtonTheme> {
  return (baseTheme: ButtonTheme) => ({
    ...baseTheme,
    colors: {
      ...baseTheme.colors,
      primary: theme.colors.onSurfaceDisabled,
    },
  });
}

