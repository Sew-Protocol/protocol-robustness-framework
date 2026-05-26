/**
 * Color primitives for theme system
 * 
 * Provides composable color utilities that work with MD3Theme
 * Inspired by wagmi's core primitives pattern
 */

import type { MD3Theme } from 'react-native-paper';
import type { ColorVariant, BackgroundVariant, ColorConfig } from './types';

// Re-export types for convenience
export type { ColorVariant, BackgroundVariant } from './types';

/**
 * Color variant mapping
 */
const COLOR_VARIANT_MAP: Record<ColorVariant, keyof MD3Theme['colors']> = {
  default: 'onSurface',
  primary: 'primary',
  secondary: 'secondary',
  error: 'error',
  success: 'onSurface', // Can be extended with custom colors
  warning: 'onSurface', // Can be extended with custom colors
};

/**
 * Background variant mapping
 */
const BACKGROUND_VARIANT_MAP: Record<BackgroundVariant, keyof MD3Theme['colors']> = {
  surface: 'surface',
  background: 'background',
  primary: 'primaryContainer',
  secondary: 'secondaryContainer',
  success: 'tertiaryContainer', // Light green for success toasts
};

/**
 * Get color by variant
 * 
 * Composable primitive - can be used independently or combined
 * 
 * @param theme - MD3Theme instance
 * @param variant - Color variant to retrieve
 * @returns Color string
 * 
 * @example
 * ```typescript
 * const primaryColor = getColorByVariant(theme, 'primary');
 * ```
 */
export function getColorByVariant(
  theme: MD3Theme,
  variant: ColorVariant = 'default'
): string {
  const colorKey = COLOR_VARIANT_MAP[variant];
  return theme.colors[colorKey] as string;
}

/**
 * Get background color by variant
 * 
 * @param theme - MD3Theme instance
 * @param variant - Background variant to retrieve
 * @returns Background color string
 * 
 * @example
 * ```typescript
 * const surfaceColor = getBackgroundColor(theme, 'surface');
 * ```
 */
export function getBackgroundColor(
  theme: MD3Theme,
  variant: BackgroundVariant = 'surface'
): string {
  const colorKey = BACKGROUND_VARIANT_MAP[variant];
  return theme.colors[colorKey] as string;
}

/**
 * Apply opacity to color
 * 
 * Note: This is a placeholder for opacity functionality.
 * For full opacity support, you may need to use a color manipulation library.
 * 
 * @param color - Color string (hex, rgb, etc.)
 * @param opacity - Opacity value (0-1)
 * @returns Color string with opacity
 */
export function withOpacity(color: string, opacity: number): string {
  // Basic implementation - can be enhanced with color manipulation library
  if (opacity >= 1) return color;
  if (opacity <= 0) return 'transparent';
  
  // For hex colors, convert to rgba
  if (color.startsWith('#')) {
    const hex = color.slice(1);
    const r = parseInt(hex.slice(0, 2), 16);
    const g = parseInt(hex.slice(2, 4), 16);
    const b = parseInt(hex.slice(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, ${opacity})`;
  }
  
  // For rgba colors, update opacity
  if (color.startsWith('rgba')) {
    return color.replace(/,\s*[\d.]+\)$/, `, ${opacity})`);
  }
  
  // For rgb colors, convert to rgba
  if (color.startsWith('rgb')) {
    return color.replace('rgb', 'rgba').replace(')', `, ${opacity})`);
  }
  
  // Fallback: return original color
  return color;
}

/**
 * Get color with configuration
 * 
 * Composable: Can be used with other color utilities
 * 
 * @param theme - MD3Theme instance
 * @param config - Color configuration
 * @returns Color string with optional opacity applied
 */
export function getColor(
  theme: MD3Theme,
  config: ColorConfig
): string {
  const color = getColorByVariant(theme, config.variant);
  
  if (config.opacity !== undefined) {
    return withOpacity(color, config.opacity);
  }
  
  return color;
}

/**
 * Get error color
 * 
 * Convenience function for error states
 */
export function getErrorColor(theme: MD3Theme): string {
  return getColorByVariant(theme, 'error');
}

/**
 * Get primary color
 * 
 * Convenience function for primary actions
 */
export function getPrimaryColor(theme: MD3Theme): string {
  return getColorByVariant(theme, 'primary');
}

/**
 * Get success color
 * 
 * Convenience function for success states
 */
export function getSuccessColor(theme: MD3Theme): string {
  return getColorByVariant(theme, 'success');
}

/**
 * Get success background color
 * 
 * Returns light green background for success toasts/messages
 */
export function getSuccessBackgroundColor(theme: MD3Theme): string {
  return getBackgroundColor(theme, 'success');
}

