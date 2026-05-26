/**
 * Theme Presets
 * 
 * Pre-built theme configurations for common use cases
 * Similar to wagmi's preset configurations
 * 
 * @example
 * ```typescript
 * const inputTheme = themePresets.primaryErrorInput(theme);
 * const buttonTheme = themePresets.disabledButton(theme);
 * ```
 */

import type { MD3Theme } from 'react-native-paper';
import { createThemeComposer } from './index';
import type { SpacingConfig } from '../core/spacing';
import type { TextInputTheme } from '../components/text-input';
import type { ButtonTheme } from '../components/button';
import type { CardTheme } from '../components/card';
import type { DialogTheme } from '../components/dialog';

/**
 * Theme Presets
 * 
 * Collection of pre-built theme configurations
 */
export const themePresets = {
  /**
   * Primary input with error state
   */
  primaryErrorInput: (theme: MD3Theme): TextInputTheme =>
    createThemeComposer(theme)
      .textInput({ variant: 'primary', size: 'medium' })
      .withError()
      .build() as unknown as TextInputTheme,

  /**
   * Disabled button
   */
  disabledButton: (theme: MD3Theme): ButtonTheme =>
    createThemeComposer(theme)
      .button({ variant: 'contained', color: 'primary' })
      .withDisabled()
      .build() as unknown as ButtonTheme,

  /**
   * Form input with spacing
   */
  formInput: (
    theme: MD3Theme,
    config?: { spacing?: SpacingConfig }
  ): TextInputTheme =>
    createThemeComposer(theme)
      .textInput({ variant: 'default', size: 'medium' })
      .withSpacing(config?.spacing || { padding: 'md', margin: 'sm' })
      .build() as unknown as TextInputTheme,

  elevatedCard: (theme: MD3Theme, elevation: number = 4): CardTheme =>
    createThemeComposer(theme)
      .card({ variant: 'surface', elevated: true })
      .withShadowPlugin({ elevation })
      .build() as unknown as CardTheme,

  // Card Presets
  /**
   * Standard card
   * Used for regular card containers
   */
  cardStandard: (theme: MD3Theme): CardTheme =>
    createThemeComposer(theme)
      .card({ variant: 'surface', elevated: false })
      .build() as unknown as CardTheme,

  /**
   * Elevated card with shadow
   * Used for prominent card containers
   */
  cardElevated: (theme: MD3Theme, elevation: number = 4): CardTheme =>
    createThemeComposer(theme)
      .card({ variant: 'surface', elevated: true })
      .withShadowPlugin({ elevation })
      .build() as unknown as CardTheme,

  /**
   * Info card with secondary background
   * Used for informational content
   */
  cardInfo: (theme: MD3Theme): CardTheme =>
    createThemeComposer(theme)
      .card({ variant: 'surface', elevated: false })
      .build() as unknown as CardTheme,

  // Dialog Presets
  /**
   * Standard dialog
   * Used for regular dialog containers
   */
  dialogStandard: (theme: MD3Theme): DialogTheme =>
    createThemeComposer(theme)
      .dialog({ variant: 'surface' })
      .build() as unknown as DialogTheme,

  /**
   * Full-screen dialog
   * Used for dialogs that should fill the screen
   */
  dialogFullScreen: (theme: MD3Theme): DialogTheme =>
    createThemeComposer(theme)
      .dialog({ variant: 'surface' })
      .build() as unknown as DialogTheme,

  // Form Input Presets
  /**
   * Standard form input
   * Used for regular form fields
   */
  formInputStandard: (theme: MD3Theme): TextInputTheme =>
    createThemeComposer(theme)
      .textInput({ variant: 'default', size: 'medium' })
      .build() as unknown as TextInputTheme,

  /**
   * Form input with error state
   * Used when validation fails
   */
  formInputError: (theme: MD3Theme): TextInputTheme =>
    createThemeComposer(theme)
      .textInput({ variant: 'default', size: 'medium' })
      .withError()
      .build() as unknown as TextInputTheme,

  /**
   * Disabled form input
   * Used when field is disabled
   */
  formInputDisabled: (theme: MD3Theme): TextInputTheme =>
    createThemeComposer(theme)
      .textInput({ variant: 'default', size: 'medium' })
      .withDisabled()
      .build() as unknown as TextInputTheme,

  /**
   * Form input with error and disabled state
   * Used when field has error and is disabled
   */
  formInputErrorDisabled: (theme: MD3Theme): TextInputTheme =>
    createThemeComposer(theme)
      .textInput({ variant: 'default', size: 'medium' })
      .withError()
      .withDisabled()
      .build() as unknown as TextInputTheme,

  // Container/Layout Presets for common patterns
  /**
   * Detail row container (flex row with spacing)
   * Used for displaying label-value pairs
   */
  detailRow: (theme: MD3Theme) => ({
    flexDirection: 'row' as const,
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 12,
    colors: {
      label: theme.colors.onSurfaceVariant,
      value: theme.colors.onSurface,
    },
  }),

  /**
   * Section title styling
   * Used for section headers
   */
  sectionTitle: (theme: MD3Theme) => ({
    fontSize: 18,
    fontWeight: 'bold' as const,
    marginBottom: 16,
    color: theme.colors.onSurface,
  }),

  /**
   * Description/text container
   * Used for displaying longer text content
   */
  descriptionContainer: (theme: MD3Theme) => ({
    marginBottom: 16,
    paddingHorizontal: 12,
    colors: {
      label: theme.colors.onSurfaceVariant,
      text: theme.colors.onSurface,
    },
  }),

  /**
   * Evidence/attachment container
   * Used for displaying evidence items with background
   */
  evidenceContainer: (theme: MD3Theme) => ({
    marginBottom: 16,
    padding: 12,
    backgroundColor: theme.colors.surfaceVariant,
    borderRadius: 8,
    colors: {
      label: theme.colors.onSurfaceVariant,
      text: theme.colors.onSurface,
      type: theme.colors.primary,
    },
  }),

  /**
   * Chat history container
   * Used for displaying chat/message history
   */
  chatContainer: (theme: MD3Theme) => ({
    marginBottom: 16,
    padding: 12,
    backgroundColor: theme.colors.surfaceVariant,
    borderRadius: 8,
    colors: {
      label: theme.colors.onSurfaceVariant,
      text: theme.colors.onSurface,
    },
  }),

  /**
   * Detail card with evidence
   * Used for dispute/transaction details with evidence support
   */
  detailCard: (theme: MD3Theme) => ({
    marginBottom: 12,
    elevation: 2,
    backgroundColor: theme.colors.surface,
    colors: {
      title: theme.colors.onSurface,
      label: theme.colors.onSurfaceVariant,
      value: theme.colors.onSurface,
    },
  }),
};

