/**
 * Design Tokens
 * 
 * Centralized design tokens for consistent styling across the application.
 * These tokens replace magic numbers and provide semantic meaning.
 */

import { getSpacing } from './spacing';
import type { SpacingScale } from './types';

/**
 * Spacing tokens for common use cases
 */
export const SPACING_TOKENS = {
  // Component-specific spacing
  buttonPadding: 'sm' as SpacingScale,
  buttonPaddingCompact: 'xs' as SpacingScale,
  cardMargin: 'md' as SpacingScale,
  cardPadding: 'md' as SpacingScale,
  sectionGap: 'lg' as SpacingScale,
  itemGap: 'md' as SpacingScale,
  
  // Layout spacing
  screenPadding: 'md' as SpacingScale,
  contentPadding: 'sm' as SpacingScale,
  sectionMargin: 'md' as SpacingScale,
  
  // Button row spacing
  buttonRowGap: 'sm' as SpacingScale,
  buttonRowMargin: 'sm' as SpacingScale,
} as const;

/**
 * Typography tokens for common text styles
 */
export const TYPOGRAPHY_TOKENS = {
  buttonLabel: {
    fontSize: 14,
    lineHeight: 20,
  },
  buttonLabelCompact: {
    fontSize: 13,
    lineHeight: 16,
  },
  buttonLabelSmall: {
    fontSize: 12,
    lineHeight: 16,
  },
  caption: {
    fontSize: 12,
    lineHeight: 16,
  },
  body: {
    fontSize: 14,
    lineHeight: 20,
  },
  bodySmall: {
    fontSize: 13,
    lineHeight: 18,
  },
} as const;

/**
 * Component-specific tokens
 */
export const COMPONENT_TOKENS = {
  // Button tokens
  button: {
    minHeight: 44, // Accessibility: minimum touch target
    minWidth: 44,
    borderRadius: 8,
    paddingHorizontal: getSpacing(SPACING_TOKENS.buttonPaddingCompact),
    paddingVertical: getSpacing(SPACING_TOKENS.buttonPadding),
  },
  
  // Card tokens
  card: {
    borderRadius: 8,
    marginBottom: getSpacing(SPACING_TOKENS.cardMargin),
    padding: getSpacing(SPACING_TOKENS.cardPadding),
  },
  
  // Escrow item tokens
  escrowItem: {
    marginBottom: getSpacing(SPACING_TOKENS.itemGap),
    borderRadius: 8,
    padding: getSpacing(SPACING_TOKENS.contentPadding),
  },
} as const;

/**
 * Get spacing token value
 * 
 * @param token - Spacing token name
 * @returns Spacing value in pixels
 */
export function getSpacingToken(token: keyof typeof SPACING_TOKENS): number {
  return getSpacing(SPACING_TOKENS[token]);
}

/**
 * Map-specific tokens
 */
export const MAP_TOKENS = {
  // Popup/Modal
  popupWidth: '80%',
  popupMaxWidth: 400,
  popupImageHeight: 200,
  popupBorderRadius: 'md' as SpacingScale,
  popupPadding: 'md' as SpacingScale,
  
  // Header
  headerPadding: 'sm' as SpacingScale,
  headerZIndex: 1000,
  
  // Buttons
  locationButtonPadding: { horizontal: 'sm' as SpacingScale, vertical: 'xs' as SpacingScale },
  locationButtonBorderRadius: 20,
  locationButtonIconGap: 'xs' as SpacingScale,
  
  // Overlays
  loadingOverlayZIndex: 100,
  modalOverlayOpacity: 0.5,
  loadingOverlayOpacity: 0.8,
  
  // Icons
  errorIconSize: 48,
  emptyStateIconSize: 48,
  locationButtonIconSize: 20,
} as const;







































































