/**
 * Typography primitives
 * 
 * Provides typography utilities for consistent text styling
 */

import type { MD3Theme } from 'react-native-paper';
import type { TypographyVariant, TypographySize, TypographyWeight, TypographyConfig } from './types';
import type { TextStyle } from 'react-native';

// Re-export types for convenience
export type { TypographySize, TypographyWeight, TypographyConfig } from './types';

/**
 * Typography variant to font size mapping
 */
const TYPOGRAPHY_SIZES: Record<TypographyVariant, Record<TypographySize, number>> = {
  headline: { small: 24, medium: 28, large: 32 },
  title: { small: 16, medium: 20, large: 24 },
  body: { small: 12, medium: 14, large: 16 },
  label: { small: 11, medium: 12, large: 14 },
  caption: { small: 10, medium: 11, large: 12 },
};

/**
 * Font weight mapping
 */
const FONT_WEIGHTS: Record<TypographyWeight, TextStyle['fontWeight']> = {
  regular: '400',
  medium: '500',
  bold: '700',
};

/**
 * Get typography style
 * 
 * @param theme - MD3Theme instance
 * @param config - Typography configuration
 * @returns TextStyle object
 * 
 * @example
 * ```typescript
 * const headlineStyle = getTypographyStyle(theme, {
 *   variant: 'headline',
 *   size: 'large',
 *   weight: 'bold'
 * });
 * ```
 */
export function getTypographyStyle(
  theme: MD3Theme,
  config: TypographyConfig
): TextStyle {
  const size = config.size || 'medium';
  const fontSize = TYPOGRAPHY_SIZES[config.variant][size];
  const fontWeight = FONT_WEIGHTS[config.weight || 'regular'];

  // Map variant to theme color
  let color = theme.colors.onSurface;
  
  if (config.variant === 'headline' || config.variant === 'title') {
    color = theme.colors.onSurface;
  } else if (config.variant === 'label') {
    color = theme.colors.onSurfaceVariant;
  } else if (config.variant === 'caption') {
    color = theme.colors.onSurfaceVariant;
  }

  return {
    fontSize,
    fontWeight,
    color,
    lineHeight: fontSize * 1.5, // Standard line height ratio
  };
}

/**
 * Get headline style
 * 
 * Convenience function for headlines
 */
export function getHeadlineStyle(
  theme: MD3Theme,
  size: TypographySize = 'medium',
  weight: TypographyWeight = 'bold'
): TextStyle {
  return getTypographyStyle(theme, {
    variant: 'headline',
    size,
    weight,
  });
}

/**
 * Get title style
 * 
 * Convenience function for titles
 */
export function getTitleStyle(
  theme: MD3Theme,
  size: TypographySize = 'medium',
  weight: TypographyWeight = 'medium'
): TextStyle {
  return getTypographyStyle(theme, {
    variant: 'title',
    size,
    weight,
  });
}

/**
 * Get body style
 * 
 * Convenience function for body text
 */
export function getBodyStyle(
  theme: MD3Theme,
  size: TypographySize = 'medium',
  weight: TypographyWeight = 'regular'
): TextStyle {
  return getTypographyStyle(theme, {
    variant: 'body',
    size,
    weight,
  });
}

/**
 * Get label style
 * 
 * Convenience function for labels
 */
export function getLabelStyle(
  theme: MD3Theme,
  size: TypographySize = 'medium',
  weight: TypographyWeight = 'medium'
): TextStyle {
  return getTypographyStyle(theme, {
    variant: 'label',
    size,
    weight,
  });
}

/**
 * Get caption style
 * 
 * Convenience function for captions
 */
export function getCaptionStyle(
  theme: MD3Theme,
  size: TypographySize = 'small',
  weight: TypographyWeight = 'regular'
): TextStyle {
  return getTypographyStyle(theme, {
    variant: 'caption',
    size,
    weight,
  });
}

