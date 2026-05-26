/**
 * Spacing primitives
 * 
 * Provides consistent spacing across components following design system patterns
 */

import type { SpacingConfig } from './types';
import type { SpacingScale } from './types';
import type { ViewStyle } from 'react-native';

// Re-export types for convenience
export type { SpacingScale, SpacingConfig } from './types';

/**
 * Spacing scale values
 * 
 * Follows Material Design spacing guidelines
 */
const SPACING_SCALE: Record<SpacingScale, number> = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  xxl: 48,
};

/**
 * Get spacing value
 * 
 * Composable: Can be used in any component
 * 
 * @param scale - Spacing scale or numeric value
 * @returns Spacing value in pixels
 * 
 * @example
 * ```typescript
 * const padding = getSpacing('md'); // 16
 * const margin = getSpacing(20); // 20
 * ```
 */
export function getSpacing(scale: SpacingScale | number): number {
  return typeof scale === 'number' ? scale : SPACING_SCALE[scale];
}

/**
 * Apply spacing to style object
 * 
 * Composable: Can be combined with other style utilities
 * 
 * @param style - Base style object
 * @param config - Spacing configuration
 * @returns Style object with spacing applied
 * 
 * @example
 * ```typescript
 * const styled = withSpacing({}, {
 *   padding: 'md',
 *   margin: 'sm',
 *   gap: 'xs'
 * });
 * ```
 */
export function withSpacing(
  style: ViewStyle,
  config: SpacingConfig
): ViewStyle {
  const result: ViewStyle = { ...style };

  if (config.padding !== undefined) {
    result.padding = getSpacing(config.padding);
  }

  if (config.margin !== undefined) {
    result.margin = getSpacing(config.margin);
  }

  if (config.gap !== undefined) {
    result.gap = getSpacing(config.gap);
  }

  if (config.paddingHorizontal !== undefined) {
    result.paddingHorizontal = getSpacing(config.paddingHorizontal);
  }

  if (config.paddingVertical !== undefined) {
    result.paddingVertical = getSpacing(config.paddingVertical);
  }

  if (config.marginHorizontal !== undefined) {
    result.marginHorizontal = getSpacing(config.marginHorizontal);
  }

  if (config.marginVertical !== undefined) {
    result.marginVertical = getSpacing(config.marginVertical);
  }

  return result;
}

/**
 * Get padding style
 * 
 * Convenience function for padding
 */
export function getPadding(padding: SpacingScale | number): ViewStyle {
  return { padding: getSpacing(padding) };
}

/**
 * Get margin style
 * 
 * Convenience function for margin
 */
export function getMargin(margin: SpacingScale | number): ViewStyle {
  return { margin: getSpacing(margin) };
}

/**
 * Get gap style
 * 
 * Convenience function for gap
 */
export function getGap(gap: SpacingScale | number): ViewStyle {
  return { gap: getSpacing(gap) };
}

