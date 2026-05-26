/**
 * Mock for useThemeComposer hook
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

