/**
 * Mock for utils/theme module
 */

const createBuilder = () => {
  const builder = {
    textInput: () => builder,
    button: () => builder,
    card: () => builder,
    dialog: () => builder,
    withError: () => builder,
    withDisabled: () => builder,
    withConditional: (condition: boolean, fn: any) => condition ? fn(builder) : builder,
    use: () => builder,
    build: () => ({}),
  };
  return builder;
};

export const useThemeComposer = () => createBuilder();

export const useThemeSystem = () => ({
  colors: {},
  spacing: {},
  typography: {},
});

export const useCardTheme = () => ({});
export const useButtonTheme = () => ({});
export const useFormInputTheme = () => ({});
export const useDialogTheme = () => ({});

export const getTextInputTheme = () => ({});
export const getButtonTheme = () => ({});
export const getCardTheme = () => ({});
export const getDialogTheme = () => ({});

export const themePresets = {};

