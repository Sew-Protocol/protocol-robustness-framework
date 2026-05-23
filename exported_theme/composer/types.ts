/**
 * Theme Composer Types
 * 
 * Type definitions for the functional theme composer system
 */

import type { MD3Theme } from 'react-native-paper';
import { ThemeComposerFunction, BaseTheme } from '../core/types';
import type { TextInputThemeConfig } from '../components/text-input';
import type { ButtonThemeConfig } from '../components/button';
import type { CardThemeConfig } from '../components/card';
import type { DialogThemeConfig } from '../components/dialog';
import type { SpacingConfig } from '../core/spacing';

/**
 * Theme plugin function
 * 
 * A plugin is a function that takes a composer and returns a new composer
 */
export type ThemePlugin<T extends BaseTheme = BaseTheme> = (
  composer: ThemeComposerFn<T>
) => ThemeComposerFn<T>;

/**
 * Theme composer function interface
 * 
 * Provides a fluent, chainable API for building theme configurations
 */
export interface ThemeComposerFn<T extends BaseTheme = BaseTheme> {
  // Chain methods
  /** Chain a custom composer function */
  chain: (composer: ThemeComposerFunction<T>) => ThemeComposerFn<T>;
  
  // Component themes
  /** Apply TextInput theme configuration */
  textInput: (config?: TextInputThemeConfig) => ThemeComposerFn<T>;
  /** Apply Button theme configuration */
  button: (config?: ButtonThemeConfig) => ThemeComposerFn<T>;
  /** Apply Card theme configuration */
  card: (config?: CardThemeConfig) => ThemeComposerFn<T>;
  /** Apply Dialog theme configuration */
  dialog: (config?: DialogThemeConfig) => ThemeComposerFn<T>;
  
  // Modifiers
  /** Apply error state */
  withError: () => ThemeComposerFn<T>;
  /** Apply disabled state */
  withDisabled: () => ThemeComposerFn<T>;
  /** Apply spacing configuration */
  withSpacing: (config: SpacingConfig) => ThemeComposerFn<T>;
  /** Apply a custom composer function */
  withCustom: (composer: ThemeComposerFunction<T>) => ThemeComposerFn<T>;
  
  // Conditional
  /** Conditionally apply a composer */
  withConditional: (
    condition: boolean,
    composer: (c: ThemeComposerFn<T>) => ThemeComposerFn<T>
  ) => ThemeComposerFn<T>;
  
  // Plugins
  /** Extend with a plugin */
  use: (plugin: ThemePlugin<T>) => ThemeComposerFn<T>;
  
  /** Apply shadow/elevation */
  withShadowPlugin: (config: { elevation: number }) => ThemeComposerFn<T>;
  
  // Build
  /** Build the final theme */
  build: () => T;
}

