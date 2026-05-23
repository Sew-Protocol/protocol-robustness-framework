/**
 * Theme Composer - Functional Implementation
 * 
 * Fluent, chainable API for building theme configurations
 * Uses functional programming patterns (closures, immutability)
 * 
 * @example
 * ```typescript
 * const inputTheme = createThemeComposer(theme)
 *   .textInput({ variant: 'primary', size: 'large' })
 *   .withError()
 *   .withSpacing({ padding: 'md', margin: 'sm' })
 *   .build();
 * ```
 */

import type { MD3Theme } from 'react-native-paper';
import { getButtonTheme } from '../components/button';
import { getCardTheme } from '../components/card';
import { getDialogTheme } from '../components/dialog';
import { getTextInputTheme, withDisabledState, withErrorState } from '../components/text-input';
import type { BaseTheme, ThemeComposerFunction } from '../core/types';
import type { ThemeComposerFn } from './types';

interface WithColors {
  colors?: Record<string, string | undefined>;
}

/**
 * Create a theme composer (functional)
 * 
 * Uses closures to maintain state immutably. Each method returns a new
 * composer instance with the added transformation, ensuring immutability.
 * 
 * @param theme - MD3Theme instance
 * @param baseTheme - Optional base theme (defaults to empty theme)
 * @param composers - Internal: accumulated composer functions
 * @returns Theme composer with chainable methods
 * 
 * @example
 * ```typescript
 * const inputTheme = createThemeComposer(theme)
 *   .textInput({ variant: 'primary' })
 *   .withError()
 *   .build();
 * ```
 */
export function createThemeComposer<T extends BaseTheme = BaseTheme>(
  theme: MD3Theme,
  baseTheme?: T,
  composers?: ThemeComposerFunction<T>[]
): ThemeComposerFn<T> {
  // Initialize state (immutable)
  const base = baseTheme || ({} as T);
  const composerList = composers || [];

  // Helper to create new composer with added transformation
  const addComposer = (
    newComposer: ThemeComposerFunction<T>
  ): ThemeComposerFn<T> => {
    return createThemeComposer(theme, base, [...composerList, newComposer]);
  };

  // Return chainable API
  return {
    // Chain a composer function
    chain: (composer) => addComposer(composer),

    // Component themes
    textInput: (config) =>
      addComposer((currentBase: T) => {
        const textInputTheme = getTextInputTheme(theme, config);
        const currentColors = (currentBase as unknown as WithColors).colors || {};
        return {
          ...currentBase,
          ...textInputTheme,
          colors: {
            ...currentColors,
            ...textInputTheme.colors,
          },
        } as T;
      }),
    
    button: (config) =>
      addComposer((currentBase: T) => {
        const buttonTheme = getButtonTheme(theme, config);
        const currentColors = (currentBase as unknown as WithColors).colors || {};
        return {
          ...currentBase,
          ...buttonTheme,
          colors: {
            ...currentColors,
            ...buttonTheme.colors,
          },
        } as T;
      }),
    
    card: (config) =>
      addComposer((currentBase: T) => {
        const cardTheme = getCardTheme(theme, config);
        return {
          ...currentBase,
          ...cardTheme,
        } as T;
      }),
    
    dialog: (config) =>
      addComposer((currentBase: T) => {
        const dialogTheme = getDialogTheme(theme, config);
        return {
          ...currentBase,
          ...dialogTheme,
        } as T;
      }),

    // Modifiers
    withError: () =>
      addComposer((currentBase: T) => {
        const res = withErrorState(theme)(currentBase as unknown as Record<string, unknown>);
        return {
          ...currentBase,
          ...(res as unknown as Record<string, unknown>),
        } as T;
      }),
    
    withDisabled: () =>
      addComposer((currentBase: T) => {
        const res = withDisabledState(theme)(currentBase as unknown as Record<string, unknown>);
        return {
          ...currentBase,
          ...(res as unknown as Record<string, unknown>),
        } as T;
      }),
    
    withSpacing: (config) =>
      addComposer((currentBase: T) => {
        // Spacing is typically applied to styles, not themes
        // This is a placeholder for potential future use
        return currentBase;
      }),
    
    withCustom: (composer) => addComposer(composer),

    // Conditional composition
    withConditional: (condition, composerFn) => {
      if (condition) {
        return composerFn(createThemeComposer(theme, base, composerList));
      }
      return createThemeComposer(theme, base, composerList);
    },

    // Plugin system
    use: (plugin) => plugin(createThemeComposer(theme, base, composerList)),

    withShadowPlugin: (config) =>
      createThemeComposer(theme, base, [
        ...composerList,
        (currentBase: T) => ({
          ...currentBase,
          elevation: config.elevation,
        } as unknown as T),
      ]),

    // Build final theme
    build: (): T => {
      return composerList.reduce(
        (acc, composer) => composer(acc),
        base
      );
    },
  };
}

// Re-export types
export type { ThemeComposerFn, ThemePlugin } from './types';

// Re-export presets and plugins
export {
  withAnimationPlugin,
  withBorderPlugin,
  withCustomStylePlugin, withShadowPlugin
} from './plugins';
export type {
  AnimationPluginConfig,
  BorderPluginConfig, ShadowPluginConfig
} from './plugins';
export { themePresets } from './presets';

