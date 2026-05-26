/**
 * useThemeComposer Hook
 * 
 * Reactive hook that returns a theme composer bound to current theme
 * Automatically updates when theme changes
 * 
 * @example
 * ```typescript
 * function MyComponent() {
 *   const composer = useThemeComposer();
 *   
 *   const inputTheme = useMemo(
 *     () => composer
 *       .textInput({ variant: 'primary' })
 *       .withError()
 *       .build(),
 *     [composer]
 *   );
 *   
 *   return <TextInput theme={inputTheme} />;
 * }
 * ```
 */

import { useTheme } from 'react-native-paper';
import { useMemo } from 'react';
import { createThemeComposer } from '../composer';
import type { BaseTheme } from '../core/types';
import type { ThemeComposerFn } from '../composer/types';

/**
 * useThemeComposer Hook
 * 
 * Returns a memoized composer bound to current theme
 * 
 * @param baseTheme - Optional base theme
 * @returns Theme composer instance
 */
export function useThemeComposer<T extends BaseTheme = BaseTheme>(
  baseTheme?: T
): ThemeComposerFn<T> {
  const theme = useTheme();
  
  return useMemo(
    () => createThemeComposer(theme, baseTheme),
    [theme, baseTheme]
  );
}

