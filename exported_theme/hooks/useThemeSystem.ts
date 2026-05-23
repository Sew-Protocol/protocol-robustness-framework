/**
 * Main theme system hook
 * 
 * Provides access to all theme utilities reactively
 * Similar to wagmi's useConfig pattern
 */

import { useTheme, MD3Theme } from 'react-native-paper';
import { useMemo } from 'react';
import type { ViewStyle } from 'react-native';
import {
  getColorByVariant,
  getBackgroundColor,
  getErrorColor,
  getPrimaryColor,
  getSuccessColor,
  type ColorVariant,
  type BackgroundVariant,
} from '../core/colors';
import {
  getSpacing,
  getPadding,
  getMargin,
  getGap,
  type SpacingScale,
  type SpacingConfig,
} from '../core/spacing';
import {
  getTypographyStyle,
  getHeadlineStyle,
  getTitleStyle,
  getBodyStyle,
  getLabelStyle,
  getCaptionStyle,
  type TypographyConfig,
  type TypographySize,
  type TypographyWeight,
} from '../core/typography';

/**
 * Theme system hook return type
 */
export interface UseThemeSystemReturn {
  theme: MD3Theme;
  colors: {
    get: (variant: ColorVariant) => string;
    background: (variant: BackgroundVariant) => string;
    error: () => string;
    primary: () => string;
    success: () => string;
  };
  spacing: {
    get: (scale: SpacingScale | number) => number;
    padding: (scale: SpacingScale | number) => ViewStyle;
    margin: (scale: SpacingScale | number) => ViewStyle;
    gap: (scale: SpacingScale | number) => ViewStyle;
  };
  typography: {
    get: (config: TypographyConfig) => ReturnType<typeof getTypographyStyle>;
    headline: (size?: TypographySize, weight?: TypographyWeight) => ReturnType<typeof getHeadlineStyle>;
    title: (size?: TypographySize, weight?: TypographyWeight) => ReturnType<typeof getTitleStyle>;
    body: (size?: TypographySize, weight?: TypographyWeight) => ReturnType<typeof getBodyStyle>;
    label: (size?: TypographySize, weight?: TypographyWeight) => ReturnType<typeof getLabelStyle>;
    caption: (size?: TypographySize, weight?: TypographyWeight) => ReturnType<typeof getCaptionStyle>;
  };
}

/**
 * Main theme system hook
 * 
 * Provides reactive access to all theme utilities
 * 
 * @returns Theme system utilities
 * 
 * @example
 * ```typescript
 * const { colors, spacing, typography } = useThemeSystem();
 * 
 * const primaryColor = colors.primary();
 * const padding = spacing.get('md');
 * const headlineStyle = typography.headline('large', 'bold');
 * ```
 */
export function useThemeSystem(): UseThemeSystemReturn {
  const theme = useTheme();

  return useMemo(() => ({
    theme,
    colors: {
      get: (variant: ColorVariant) => getColorByVariant(theme, variant),
      background: (variant: BackgroundVariant) => getBackgroundColor(theme, variant),
      error: () => getErrorColor(theme),
      primary: () => getPrimaryColor(theme),
      success: () => getSuccessColor(theme),
    },
    spacing: {
      get: (scale: SpacingScale | number) => getSpacing(scale),
      padding: (scale: SpacingScale | number) => getPadding(scale),
      margin: (scale: SpacingScale | number) => getMargin(scale),
      gap: (scale: SpacingScale | number) => getGap(scale),
    },
    typography: {
      get: (config: TypographyConfig) => getTypographyStyle(theme, config),
      headline: (size?: TypographySize, weight?: TypographyWeight) => 
        getHeadlineStyle(theme, size, weight),
      title: (size?: TypographySize, weight?: TypographyWeight) => 
        getTitleStyle(theme, size, weight),
      body: (size?: TypographySize, weight?: TypographyWeight) => 
        getBodyStyle(theme, size, weight),
      label: (size?: TypographySize, weight?: TypographyWeight) => 
        getLabelStyle(theme, size, weight),
      caption: (size?: TypographySize, weight?: TypographyWeight) => 
        getCaptionStyle(theme, size, weight),
    },
  }), [theme]);
}

