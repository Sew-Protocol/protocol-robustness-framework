/**
 * Theme Plugin System
 * 
 * Allows extending the composer with custom functionality
 * Similar to wagmi's plugin system
 * 
 * @example
 * ```typescript
 * const cardTheme = createThemeComposer(theme)
 *   .card({ variant: 'elevated' })
 *   .use(withShadowPlugin({ elevation: 8 }))
 *   .build();
 * ```
 */

import type { ThemePlugin } from './types';
import type { BaseTheme } from '../core/types';

/**
 * Shadow plugin configuration
 */
export interface ShadowPluginConfig {
  elevation?: number;
  shadowColor?: string;
  shadowOpacity?: number;
  shadowOffset?: { width: number; height: number };
  shadowRadius?: number;
}

/**
 * Shadow plugin
 * 
 * Adds shadow/elevation styles to theme
 * 
 * @param config - Shadow configuration
 * @returns Theme plugin
 */
export function withShadowPlugin(
  config: ShadowPluginConfig = {}
): ThemePlugin {
  return (composer) => {
    return composer.withCustom((base) => ({
      ...base,
      shadowColor: config.shadowColor || '#000',
      shadowOffset: config.shadowOffset || { width: 0, height: 2 },
      shadowOpacity: config.shadowOpacity ?? 0.1,
      shadowRadius: config.shadowRadius || config.elevation || 4,
      elevation: config.elevation || 4,
    } as BaseTheme));
  };
}

/**
 * Animation plugin configuration
 */
export interface AnimationPluginConfig {
  duration?: number;
  easing?: string;
}

/**
 * Animation plugin
 * 
 * Adds animation/transition styles to theme
 * 
 * @param config - Animation configuration
 * @returns Theme plugin
 */
export function withAnimationPlugin(
  config: AnimationPluginConfig = {}
): ThemePlugin {
  return (composer) => {
    return composer.withCustom((base) => ({
      ...base,
      transitionDuration: config.duration || 200,
      transitionTimingFunction: config.easing || 'ease-in-out',
    } as BaseTheme));
  };
}

/**
 * Border plugin configuration
 */
export interface BorderPluginConfig {
  width?: number;
  color?: string;
  radius?: number;
  style?: 'solid' | 'dashed' | 'dotted';
}

/**
 * Border plugin
 * 
 * Adds border styles to theme
 * 
 * @param config - Border configuration
 * @returns Theme plugin
 */
export function withBorderPlugin(
  config: BorderPluginConfig = {}
): ThemePlugin {
  return (composer) => {
    return composer.withCustom((base) => ({
      ...base,
      borderWidth: config.width || 1,
      borderColor: config.color || '#000',
      borderRadius: config.radius || 0,
      borderStyle: config.style || 'solid',
    } as BaseTheme));
  };
}

/**
 * Custom style plugin
 * 
 * Adds custom styles to theme
 * 
 * @param styles - Custom style object
 * @returns Theme plugin
 */
export function withCustomStylePlugin(
  styles: Record<string, any>
): ThemePlugin {
  return (composer) => {
    return composer.withCustom((base) => ({
      ...base,
      ...styles,
    } as BaseTheme));
  };
}

