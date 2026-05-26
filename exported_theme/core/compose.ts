/**
 * Composition utility
 * 
 * Allows composing multiple theme modifiers together
 * Inspired by functional programming composition patterns
 */

import type { ThemeComposer } from './types';

/**
 * Compose multiple theme composers
 * 
 * Applies composers from right to left (functional composition order)
 * 
 * @param composers - Array of theme composer functions
 * @returns Composed theme composer function
 * 
 * @example
 * ```typescript
 * const composed = compose(
 *   withErrorState(theme),
 *   withSpacing({ padding: 'md' }),
 *   withPrimaryColor(theme)
 * );
 * 
 * const finalTheme = composed(baseTheme);
 * ```
 */
export function compose<T>(...composers: ThemeComposer<T>[]): ThemeComposer<T> {
  return (base: T) => {
    return composers.reduceRight((acc, composer) => composer(acc), base);
  };
}

/**
 * Pipe multiple theme composers
 * 
 * Applies composers from left to right (more intuitive order)
 * 
 * @param composers - Array of theme composer functions
 * @returns Composed theme composer function
 * 
 * @example
 * ```typescript
 * const piped = pipe(
 *   withPrimaryColor(theme),
 *   withSpacing({ padding: 'md' }),
 *   withErrorState(theme)
 * );
 * 
 * const finalTheme = piped(baseTheme);
 * ```
 */
export function pipe<T>(...composers: ThemeComposer<T>[]): ThemeComposer<T> {
  return (base: T) => {
    return composers.reduce((acc, composer) => composer(acc), base);
  };
}

