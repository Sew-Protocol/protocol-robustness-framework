/**
 * Core type definitions for theme system
 * 
 * Provides type-safe interfaces for theme configuration
 */

import type { MD3Theme } from 'react-native-paper';
import type { TextStyle, ViewStyle } from 'react-native';

/**
 * Color variant types
 */
export type ColorVariant = 
  | 'default' 
  | 'primary' 
  | 'secondary' 
  | 'error' 
  | 'success' 
  | 'warning';

/**
 * Background variant types
 */
export type BackgroundVariant = 
  | 'surface' 
  | 'background' 
  | 'primary' 
  | 'secondary'
  | 'success';

/**
 * Spacing scale types
 */
export type SpacingScale = 'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'xxl';

/**
 * Typography variant types
 */
export type TypographyVariant = 
  | 'headline'
  | 'title'
  | 'body'
  | 'label'
  | 'caption';

/**
 * Typography size types
 */
export type TypographySize = 'small' | 'medium' | 'large';

/**
 * Typography weight types
 */
export type TypographyWeight = 'regular' | 'medium' | 'bold';

/**
 * Component size types
 */
export type ComponentSize = 'small' | 'medium' | 'large';

/**
 * Base theme interface
 */
export interface BaseTheme {
  theme: MD3Theme;
}

/**
 * Color configuration
 */
export interface ColorConfig {
  variant?: ColorVariant;
  opacity?: number;
}

/**
 * Spacing configuration
 */
export interface SpacingConfig {
  padding?: SpacingScale | number;
  margin?: SpacingScale | number;
  gap?: SpacingScale | number;
  paddingHorizontal?: SpacingScale | number;
  paddingVertical?: SpacingScale | number;
  marginHorizontal?: SpacingScale | number;
  marginVertical?: SpacingScale | number;
}

/**
 * Typography configuration
 */
export interface TypographyConfig {
  variant: TypographyVariant;
  size?: TypographySize;
  weight?: TypographyWeight;
}

/**
 * Theme style result
 */
export type ThemeStyle = ViewStyle & TextStyle;

/**
 * Composer function type
 */
export type ThemeComposer<T> = (base: T) => T;

/**
 * Theme composer function type (alias for ThemeComposer)
 * Used for consistency with composer API
 */
export type ThemeComposerFunction<T extends BaseTheme = BaseTheme> = ThemeComposer<T>;

