/**
 * Button Style Presets
 * 
 * Provides StyleSheet-based button styles for consistent button styling.
 * These complement the button theme system with actual style objects.
 */

import { StyleSheet, type ViewStyle, type TextStyle } from 'react-native';
import type { MD3Theme } from 'react-native-paper';
import { getSpacing, withSpacing } from '../core/spacing';
import { getLabelStyle, getCaptionStyle } from '../core/typography';

/**
 * Button style configuration
 */
export interface ButtonStyleConfig {
  /** Button size variant */
  size?: 'small' | 'medium' | 'large' | 'compact';
  /** Full width button */
  fullWidth?: boolean;
  /** Compact padding */
  compact?: boolean;
}

/**
 * Create button styles
 * 
 * @param theme - MD3Theme instance
 * @param config - Button style configuration
 * @returns StyleSheet with button styles
 */
export function createButtonStyles(
  theme: MD3Theme,
  config: ButtonStyleConfig = {}
): ReturnType<typeof StyleSheet.create> {
  const { size = 'medium', fullWidth = false, compact = false } = config;

  return StyleSheet.create({
    base: {
      borderRadius: 8,
      minHeight: 44, // Accessibility: minimum touch target (iOS)
      minWidth: 44,
    } as ViewStyle,

    fullWidth: {
      width: '100%',
    } as ViewStyle,

    // Escrow action buttons (compact, side-by-side)
    escrowAction: {
      flex: 1,
      ...withSpacing({}, {
        paddingHorizontal: 'xs',
        paddingVertical: 'sm',
      }),
    } as ViewStyle,

    escrowActionContent: {
      paddingHorizontal: getSpacing('xs'),
      paddingVertical: getSpacing('sm'),
    } as ViewStyle,

    escrowActionLabel: {
      ...getCaptionStyle(theme, 'small'),
      lineHeight: 16,
    } as TextStyle,

    // Primary action button (full width, standard padding)
    primaryAction: {
      width: '100%',
      ...withSpacing({}, {
        margin: 'sm',
      }),
    } as ViewStyle,

    primaryActionContent: {
      paddingVertical: getSpacing('md'),
    } as ViewStyle,

    primaryActionLabel: {
      ...getLabelStyle(theme, 'medium'),
    } as TextStyle,

    // Compact button variant
    compact: {
      ...withSpacing({}, {
        paddingHorizontal: 'xs',
        paddingVertical: 'xs',
      }),
    } as ViewStyle,

    compactContent: {
      paddingHorizontal: getSpacing('xs'),
      paddingVertical: getSpacing('xs'),
    } as ViewStyle,

    compactLabel: {
      ...getCaptionStyle(theme, 'small'),
    } as TextStyle,

    // Row container for side-by-side buttons
    buttonRow: {
      flexDirection: 'row',
      width: '100%',
      gap: getSpacing('sm'),
      ...withSpacing({}, {
        margin: 'sm',
      }),
    } as ViewStyle,
  });
}

/**
 * Button style presets for common use cases
 */
export const BUTTON_STYLE_PRESETS = {
  /**
   * Escrow action button (Request Refund, Raise Dispute)
   * Used in side-by-side button layouts
   */
  escrowAction: {
    style: { flex: 1 } as ViewStyle,
    contentStyle: {
      paddingHorizontal: getSpacing('xs'),
      paddingVertical: getSpacing('sm'),
    } as ViewStyle,
    labelStyle: {
      fontSize: 13,
      lineHeight: 16,
    } as TextStyle,
  },

  /**
   * Primary action button (Surrender Deposit, Release Payment)
   * Full width, standard padding
   */
  primaryAction: {
    style: {
      width: '100%',
      marginTop: getSpacing('sm'),
    } as ViewStyle,
    contentStyle: {
      paddingVertical: getSpacing('md'),
    } as ViewStyle,
    labelStyle: {
      fontSize: 14,
      lineHeight: 20,
    } as TextStyle,
  },

  /**
   * Compact button variant
   */
  compact: {
    style: {} as ViewStyle,
    contentStyle: {
      paddingHorizontal: getSpacing('xs'),
      paddingVertical: getSpacing('xs'),
    } as ViewStyle,
    labelStyle: {
      fontSize: 12,
      lineHeight: 16,
    } as TextStyle,
  },
} as const;













































































