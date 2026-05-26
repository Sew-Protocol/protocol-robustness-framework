/**
 * TextInput theme primitives
 * 
 * Composable and extensible TextInput theming utilities
 */

import type { MD3Theme } from 'react-native-paper';
import { getBackgroundColor, getColorByVariant, getErrorColor } from '../core/colors';
import type { ColorVariant, ComponentSize, SpacingConfig, ThemeComposer, BaseTheme } from '../core/types';

/**
 * TextInput theme type
 * Compatible with react-native-paper's TextInput theme prop
 */
export interface TextInputTheme extends BaseTheme {
  colors: {
    background?: string;
    onSurface?: string;
    text?: string;
    placeholder?: string;
    primary?: string;
    error?: string;
  };
}

/**
 * TextInput theme configuration
 */
export interface TextInputThemeConfig {
  variant?: ColorVariant;
  size?: ComponentSize;
  disabled?: boolean;
  spacing?: SpacingConfig;
}

/**
 * Base TextInput theme
 * 
 * Composable: Can be extended with other utilities
 * 
 * @param theme - MD3Theme instance
 * @param config - TextInput theme configuration
 * @returns TextInput theme object
 * 
 * @example
 * ```typescript
 * const inputTheme = getTextInputTheme(theme, {
 *   variant: 'primary',
 *   size: 'large'
 * });
 * ```
 */
export function getTextInputTheme(
  theme: MD3Theme,
  config: TextInputThemeConfig = {}
): TextInputTheme {
  const variant = config.variant || 'default';
  const isDisabled = config.disabled || false;

  const baseTheme: TextInputTheme = {
    theme,
    colors: {
      background: getBackgroundColor(theme, 'surface'),
      onSurface: isDisabled 
        ? theme.colors.onSurfaceDisabled 
        : getColorByVariant(theme, variant),
      text: isDisabled 
        ? theme.colors.onSurfaceDisabled 
        : theme.colors.onSurface,
      placeholder: theme.colors.onSurfaceVariant,
    },
  };

  // Add primary color if variant is primary
  if (variant === 'primary') {
    baseTheme.colors.primary = getColorByVariant(theme, 'primary');
  }

  // Add error color if variant is error
  if (variant === 'error') {
    baseTheme.colors.error = getErrorColor(theme);
  }

  return baseTheme;
}

/**
 * TextInput theme with error state
 * 
 * Composable: Can be combined with other modifiers
 * 
 * @param theme - MD3Theme instance
 * @returns Theme composer function
 */
export function withErrorState(theme: MD3Theme): ThemeComposer<TextInputTheme> {
  return (baseTheme: TextInputTheme) => ({
    ...baseTheme,
    colors: {
      ...baseTheme.colors,
      error: getErrorColor(theme),
      onSurface: getErrorColor(theme),
    },
  });
}

/**
 * TextInput theme with disabled state
 * 
 * Composable: Can be combined with other modifiers
 * 
 * @param theme - MD3Theme instance
 * @returns Theme composer function
 */
export function withDisabledState(theme: MD3Theme): ThemeComposer<TextInputTheme> {
  return (baseTheme: TextInputTheme) => ({
    ...baseTheme,
    colors: {
      ...baseTheme.colors,
      onSurface: theme.colors.onSurfaceDisabled,
      text: theme.colors.onSurfaceDisabled,
    },
  });
}

/**
 * TextInput theme with primary color
 * 
 * Composable: Can be combined with other modifiers
 * 
 * @param theme - MD3Theme instance
 * @returns Theme composer function
 */
export function withPrimaryColor(theme: MD3Theme): ThemeComposer<TextInputTheme> {
  return (baseTheme: TextInputTheme) => ({
    ...baseTheme,
    colors: {
      ...baseTheme.colors,
      primary: getColorByVariant(theme, 'primary'),
    },
  });
}

/**
 * TextInput theme with spacing
 * 
 * Note: Spacing is typically applied to the container, not the theme itself.
 * This is included for completeness but may not be used directly.
 * 
 * @param config - Spacing configuration
 * @returns Theme composer function
 */
export function withTextInputSpacing(config: SpacingConfig): ThemeComposer<TextInputTheme> {
  return (baseTheme: TextInputTheme) => {
    // Spacing is usually applied to the component style, not the theme
    // This is a placeholder for potential future use
    return baseTheme;
  };
}

/**
 * Get TextInput theme with primary color (backward compatibility)
 * 
 * @deprecated Use getTextInputTheme with variant: 'primary' instead
 */
export function getTextInputThemeWithPrimary(theme: MD3Theme): TextInputTheme {
  return getTextInputTheme(theme, { variant: 'primary' });
}

/**
 * Get disabled TextInput theme (backward compatibility)
 * 
 * @deprecated Use getTextInputTheme with disabled: true instead
 */
export function getDisabledTextInputTheme(theme: MD3Theme): TextInputTheme {
  return getTextInputTheme(theme, { disabled: true });
}

