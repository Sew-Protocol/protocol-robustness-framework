/**
 * Common Style Utilities
 * 
 * Provides reusable style patterns to replace inline styles throughout the codebase.
 * These styles use design tokens for consistency.
 */

import { StyleSheet, type ViewStyle, type TextStyle } from 'react-native';
import { getSpacing } from './core/spacing';
import { SPACING_TOKENS, TYPOGRAPHY_TOKENS } from './core/tokens';
import type { MD3Theme } from 'react-native-paper';

/**
 * Common layout styles (theme-independent)
 */
export const commonLayoutStyles = StyleSheet.create({
  // Flexbox patterns
  row: {
    flexDirection: 'row',
  } as ViewStyle,

  rowCentered: {
    flexDirection: 'row',
    alignItems: 'center',
  } as ViewStyle,

  rowSpaceBetween: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  } as ViewStyle,

  rowWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  } as ViewStyle,

  column: {
    flexDirection: 'column',
  } as ViewStyle,

  columnCentered: {
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
  } as ViewStyle,

  // Width/Height patterns
  fullWidth: {
    width: '100%',
  } as ViewStyle,

  fullHeight: {
    height: '100%',
  } as ViewStyle,

  flex1: {
    flex: 1,
  } as ViewStyle,

  // Common spacing patterns
  marginTopXs: {
    marginTop: getSpacing('xs'),
  } as ViewStyle,

  marginTopSm: {
    marginTop: getSpacing('sm'),
  } as ViewStyle,

  marginTopMd: {
    marginTop: getSpacing('md'),
  } as ViewStyle,

  marginTopLg: {
    marginTop: getSpacing('lg'),
  } as ViewStyle,

  marginBottomXs: {
    marginBottom: getSpacing('xs'),
  } as ViewStyle,

  marginBottomSm: {
    marginBottom: getSpacing('sm'),
  } as ViewStyle,

  marginBottomMd: {
    marginBottom: getSpacing('md'),
  } as ViewStyle,

  marginBottomLg: {
    marginBottom: getSpacing('lg'),
  } as ViewStyle,

  marginHorizontalSm: {
    marginHorizontal: getSpacing('sm'),
  } as ViewStyle,

  marginHorizontalMd: {
    marginHorizontal: getSpacing('md'),
  } as ViewStyle,

  marginVerticalSm: {
    marginVertical: getSpacing('sm'),
  } as ViewStyle,

  marginVerticalMd: {
    marginVertical: getSpacing('md'),
  } as ViewStyle,

  paddingSm: {
    padding: getSpacing('sm'),
  } as ViewStyle,

  paddingMd: {
    padding: getSpacing('md'),
  } as ViewStyle,

  paddingLg: {
    padding: getSpacing('lg'),
  } as ViewStyle,

  paddingHorizontalSm: {
    paddingHorizontal: getSpacing('sm'),
  } as ViewStyle,

  paddingHorizontalMd: {
    paddingHorizontal: getSpacing('md'),
  } as ViewStyle,

  paddingVerticalSm: {
    paddingVertical: getSpacing('sm'),
  } as ViewStyle,

  paddingVerticalMd: {
    paddingVertical: getSpacing('md'),
  } as ViewStyle,

  // Gap patterns
  gapXs: {
    gap: getSpacing('xs'),
  } as ViewStyle,

  gapSm: {
    gap: getSpacing('sm'),
  } as ViewStyle,

  gapMd: {
    gap: getSpacing('md'),
  } as ViewStyle,

  gapLg: {
    gap: getSpacing('lg'),
  } as ViewStyle,

  // Centering helper (horizontal + vertical)
  centered: {
    alignItems: 'center',
    justifyContent: 'center',
  } as ViewStyle,
});

/**
 * Create theme-aware common styles
 */
export function createCommonStyles(theme: MD3Theme) {
  return StyleSheet.create({
    // Text color styles
    textOnSurface: {
      color: theme.colors.onSurface,
    } as TextStyle,

    textOnSurfaceVariant: {
      color: theme.colors.onSurfaceVariant,
    } as TextStyle,

    textPrimary: {
      color: theme.colors.primary,
    } as TextStyle,

    textSecondary: {
      color: theme.colors.secondary,
    } as TextStyle,

    textError: {
      color: theme.colors.error,
    } as TextStyle,

    // Background colors
    backgroundSurface: {
      backgroundColor: theme.colors.surface,
    } as ViewStyle,

    backgroundSurfaceVariant: {
      backgroundColor: theme.colors.surfaceVariant,
    } as ViewStyle,

    backgroundPrimary: {
      backgroundColor: theme.colors.primary,
    } as ViewStyle,

    backgroundError: {
      backgroundColor: theme.colors.error,
    } as ViewStyle,

    // Typography styles using tokens
    textButtonLabel: {
      ...TYPOGRAPHY_TOKENS.buttonLabel,
      color: theme.colors.onPrimary,
    } as TextStyle,

    textButtonLabelCompact: {
      ...TYPOGRAPHY_TOKENS.buttonLabelCompact,
      color: theme.colors.onPrimary,
    } as TextStyle,

    textCaption: {
      ...TYPOGRAPHY_TOKENS.caption,
      color: theme.colors.onSurfaceVariant,
    } as TextStyle,

    textBody: {
      ...TYPOGRAPHY_TOKENS.body,
      color: theme.colors.onSurface,
    } as TextStyle,

    textBodySmall: {
      ...TYPOGRAPHY_TOKENS.bodySmall,
      color: theme.colors.onSurface,
    } as TextStyle,
  });
}

/**
 * Common button content styles (for react-native-paper Button)
 */
export function createButtonContentStyles() {
  return StyleSheet.create({
    compact: {
      paddingHorizontal: getSpacing(SPACING_TOKENS.buttonPaddingCompact),
      paddingVertical: getSpacing(SPACING_TOKENS.buttonPadding),
    } as ViewStyle,

    standard: {
      paddingHorizontal: getSpacing(SPACING_TOKENS.buttonPadding),
      paddingVertical: getSpacing(SPACING_TOKENS.buttonPadding),
    } as ViewStyle,
  });
}

/**
 * Common button label styles (for react-native-paper Button)
 */
export function createButtonLabelStyles(theme: MD3Theme) {
  return StyleSheet.create({
    standard: {
      ...TYPOGRAPHY_TOKENS.buttonLabel,
      color: theme.colors.onPrimary,
    } as TextStyle,

    compact: {
      ...TYPOGRAPHY_TOKENS.buttonLabelCompact,
      color: theme.colors.onPrimary,
    } as TextStyle,

    small: {
      ...TYPOGRAPHY_TOKENS.buttonLabelSmall,
      color: theme.colors.onPrimary,
    } as TextStyle,

    outlined: {
      ...TYPOGRAPHY_TOKENS.buttonLabel,
      color: theme.colors.primary,
    } as TextStyle,

    text: {
      ...TYPOGRAPHY_TOKENS.buttonLabel,
      color: theme.colors.primary,
    } as TextStyle,
  });
}

/**
 * Screen container styles
 */
export const screenStyles = StyleSheet.create({
  container: {
    flex: 1,
    padding: getSpacing(SPACING_TOKENS.screenPadding),
  } as ViewStyle,

  scrollContainer: {
    flexGrow: 1,
    padding: getSpacing(SPACING_TOKENS.screenPadding),
  } as ViewStyle,

  content: {
    padding: getSpacing(SPACING_TOKENS.contentPadding),
  } as ViewStyle,
});

/**
 * Card styles
 */
export function createCardStyles(theme: MD3Theme) {
  return StyleSheet.create({
    card: {
      borderRadius: 8,
      marginBottom: getSpacing(SPACING_TOKENS.cardMargin),
      padding: getSpacing(SPACING_TOKENS.cardPadding),
      backgroundColor: theme.colors.surface,
    } as ViewStyle,

    cardContent: {
      padding: getSpacing(SPACING_TOKENS.contentPadding),
    } as ViewStyle,
  });
}

/**
 * Section styles
 */
export const sectionStyles = StyleSheet.create({
  section: {
    marginBottom: getSpacing(SPACING_TOKENS.sectionMargin),
  } as ViewStyle,

  sectionGap: {
    gap: getSpacing(SPACING_TOKENS.sectionGap),
  } as ViewStyle,
});

/**
 * Helper to combine styles
 */
export function combineStyles(...styles: (ViewStyle | TextStyle | undefined | null | false)[]): ViewStyle | TextStyle {
  return StyleSheet.flatten(styles.filter(Boolean) as (ViewStyle | TextStyle)[]);
}

