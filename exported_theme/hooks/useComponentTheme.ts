/**
 * Composable component theme hooks
 * 
 * ⚠️ DEPRECATED: This file contains legacy hooks that use direct theme functions.
 * 
 * All hooks in this file have been replaced with composer-based hooks:
 * - `useTextInputTheme` → Use `useFormInputTheme` from `hooks/useFormInputTheme`
 * - `useButtonTheme` → Use `useButtonTheme` from `hooks/useButtonTheme` (composer-based)
 * - `useCardTheme` → Use `useCardTheme` from `hooks/useCardTheme` (composer-based)
 * - `useDialogTheme` → Use `useDialogTheme` from `hooks/useDialogTheme` (composer-based)
 * 
 * This file is maintained for backward compatibility only and will be removed in a future version.
 * 
 * @deprecated Use composer-based hooks from `hooks/` directory instead
 */

import { useTheme } from 'react-native-paper';
import { useMemo } from 'react';
import {
  getTextInputTheme,
  type TextInputThemeConfig,
} from '../components/text-input';
import {
  getButtonTheme,
  type ButtonThemeConfig,
} from '../components/button';
import {
  getCardTheme,
  type CardThemeConfig,
} from '../components/card';
import {
  getDialogTheme,
  type DialogThemeConfig,
} from '../components/dialog';
import { logger } from './../../logger';

// Track warnings to avoid console spam
let hasWarnedUseTextInputTheme = false;
let hasWarnedUseButtonTheme = false;
let hasWarnedUseCardTheme = false;
let hasWarnedUseDialogTheme = false;

/**
 * TextInput theme hook
 * 
 * @deprecated Use `useFormInputTheme` from `hooks/useFormInputTheme` instead
 * 
 * @example
 * ```typescript
 * // ❌ Old (deprecated)
 * import { useTextInputTheme } from '@/utils/theme';
 * 
 * // ✅ New (recommended)
 * import { useFormInputTheme } from '@/hooks/useFormInputTheme';
 * ```
 */
export function useTextInputTheme(config: TextInputThemeConfig = {}) {
  if (!hasWarnedUseTextInputTheme && __DEV__) {
    logger.warn(
      '⚠️ [DEPRECATED] useTextInputTheme from "@/utils/theme/hooks/useComponentTheme" is deprecated.\n' +
      '   Use useFormInputTheme from "@/hooks/useFormInputTheme" instead.\n' +
      '   Migration: import { useFormInputTheme } from "@/hooks/useFormInputTheme";'
    );
    hasWarnedUseTextInputTheme = true;
  }

  const theme = useTheme();

  return useMemo(
    () => getTextInputTheme(theme, config),
    [theme, config.variant, config.size, config.disabled]
  );
}

/**
 * Button theme hook
 * 
 * @deprecated Use `useButtonTheme` from `hooks/useButtonTheme` instead (composer-based)
 * 
 * @example
 * ```typescript
 * // ❌ Old (deprecated)
 * import { useButtonTheme } from '@/utils/theme/hooks/useComponentTheme';
 * 
 * // ✅ New (recommended)
 * import { useButtonTheme } from '@/hooks/useButtonTheme';
 * ```
 */
export function useButtonTheme(config: ButtonThemeConfig = {}) {
  if (!hasWarnedUseButtonTheme && __DEV__) {
    logger.warn(
      '⚠️ [DEPRECATED] useButtonTheme from "@/utils/theme/hooks/useComponentTheme" is deprecated.\n' +
      '   Use useButtonTheme from "@/hooks/useButtonTheme" instead (composer-based).\n' +
      '   Migration: import { useButtonTheme } from "@/hooks/useButtonTheme";'
    );
    hasWarnedUseButtonTheme = true;
  }

  const theme = useTheme();

  return useMemo(
    () => getButtonTheme(theme, config),
    [theme, config.variant, config.color, config.size, config.disabled]
  );
}

/**
 * Card theme hook
 * 
 * @deprecated Use `useCardTheme` from `hooks/useCardTheme` instead (composer-based)
 */
export function useCardTheme(config: CardThemeConfig = {}) {
  if (!hasWarnedUseCardTheme && __DEV__) {
    logger.warn(
      '⚠️ [DEPRECATED] useCardTheme from "@/utils/theme/hooks/useComponentTheme" is deprecated.\n' +
      '   Use useCardTheme from "@/hooks/useCardTheme" instead (composer-based).\n' +
      '   Migration: import { useCardTheme } from "@/hooks/useCardTheme";'
    );
    hasWarnedUseCardTheme = true;
  }

  const theme = useTheme();

  return useMemo(
    () => getCardTheme(theme, config),
    [theme, config.variant, config.elevated]
  );
}

/**
 * Dialog theme hook
 * 
 * @deprecated Use `useDialogTheme` from `hooks/useDialogTheme` instead (composer-based)
 */
export function useDialogTheme(config: DialogThemeConfig = {}) {
  if (!hasWarnedUseDialogTheme && __DEV__) {
    logger.warn(
      '⚠️ [DEPRECATED] useDialogTheme from "@/utils/theme/hooks/useComponentTheme" is deprecated.\n' +
      '   Use useDialogTheme from "@/hooks/useDialogTheme" instead (composer-based).\n' +
      '   Migration: import { useDialogTheme } from "@/hooks/useDialogTheme";'
    );
    hasWarnedUseDialogTheme = true;
  }

  const theme = useTheme();

  return useMemo(
    () => getDialogTheme(theme, config),
    [theme, config.variant]
  );
}

