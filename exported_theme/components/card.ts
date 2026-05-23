/**
 * Card theme primitives
 * 
 * Provides theming utilities for Card components
 */

import type { MD3Theme } from 'react-native-paper';
import { getBackgroundColor } from '../core/colors';
import type { BackgroundVariant, ThemeComposer, BaseTheme } from '../core/types';

/**
 * Card theme configuration
 */
export interface CardThemeConfig {
  variant?: BackgroundVariant;
  elevated?: boolean;
}

/**
 * Card theme type (simplified for now)
 */
export interface CardTheme extends BaseTheme {
  backgroundColor?: string;
  elevation?: number;
}

/**
 * Base Card theme
 * 
 * @param theme - MD3Theme instance
 * @param config - Card theme configuration
 * @returns Card theme object
 */
export function getCardTheme(
  theme: MD3Theme,
  config: CardThemeConfig = {}
): CardTheme {
  const variant = config.variant || 'surface';
  const elevated = config.elevated || false;

  return {
    theme,
    backgroundColor: getBackgroundColor(theme, variant),
    elevation: elevated ? 2 : 0,
  };
}

/**
 * Card theme with elevation
 * 
 * Composable: Can be combined with other modifiers
 */
export function withCardElevation(elevation: number): ThemeComposer<CardTheme> {
  return (baseTheme: CardTheme) => ({
    ...baseTheme,
    elevation,
  });
}

