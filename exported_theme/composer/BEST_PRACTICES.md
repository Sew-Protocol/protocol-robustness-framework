# Theme Composer Best Practices

Guidelines for effective use of the Theme Composer API.

## Core Principles

### 1. **Use Presets for Common Patterns**

Presets encapsulate best practices and ensure consistency across the app.

```typescript
// ✅ Good: Use presets
const cardTheme = themePresets.cardStandard(theme);

// ❌ Avoid: Building from scratch every time
const cardTheme = createThemeComposer(theme)
  .card({ variant: 'surface', elevated: false })
  .build();
```

### 2. **Memoize Theme Computations**

Theme composition is pure, so memoize results to avoid unnecessary recalculations.

```typescript
// ✅ Good: Memoize theme composition
const inputTheme = useMemo(
  () => themePresets.formInputStandard(theme),
  [theme]
);

// ❌ Avoid: Recomputing on every render
function MyComponent() {
  const inputTheme = themePresets.formInputStandard(theme);
  return <TextInput theme={inputTheme} />;
}
```

### 3. **Compose Conditionally, Not Imperatively**

Use `.withConditional()` for conditional composition rather than building different themes.

```typescript
// ✅ Good: Conditional composition
const inputTheme = createThemeComposer(theme)
  .textInput({ variant: 'primary' })
  .withConditional(hasError, (c) => c.withError())
  .withConditional(isDisabled, (c) => c.withDisabled())
  .build();

// ❌ Avoid: Building different themes imperatively
let inputTheme;
if (hasError) {
  inputTheme = themePresets.formInputError(theme);
} else {
  inputTheme = themePresets.formInputStandard(theme);
}
```

## Component-Specific Guidelines

### Input Fields

Use form input presets for consistent input styling:

```typescript
// Standard input
const theme = themePresets.formInputStandard(theme);

// Input with error state
const theme = themePresets.formInputError(theme);

// Disabled input
const theme = themePresets.formInputDisabled(theme);

// Error + disabled
const theme = themePresets.formInputErrorDisabled(theme);
```

### Cards

Use card presets based on visual prominence:

```typescript
// Default card (light background, no shadow)
const theme = themePresets.cardStandard(theme);

// Prominent card (elevated with shadow)
const theme = themePresets.cardElevated(theme, 6);

// Information card (secondary background)
const theme = themePresets.cardInfo(theme);
```

### Dialogs

Use dialog presets for modal and full-screen dialogs:

```typescript
// Standard modal dialog
const theme = themePresets.dialogStandard(theme);

// Full-screen dialog (e.g., bottom sheet, modal form)
const theme = themePresets.dialogFullScreen(theme);
```

### Buttons

Use button presets for consistency:

```typescript
// Disabled button
const theme = themePresets.disabledButton(theme);

// Custom button composition
const theme = createThemeComposer(theme)
  .button({ variant: 'contained', color: 'primary' })
  .build();
```

## Plugin Usage

Plugins extend theme composition with additional capabilities like shadows, borders, and animations.

### Shadows

Add elevation and shadows to cards and prominent elements:

```typescript
const { withShadowPlugin } = require('@/utils/theme/composer/plugins');

const shadowedCard = createThemeComposer(theme)
  .card({ variant: 'elevated' })
  .use(withShadowPlugin({ elevation: 8 }))
  .build();
```

### Borders

Add borders to components:

```typescript
const { withBorderPlugin } = require('@/utils/theme/composer/plugins');

const borderedCard = createThemeComposer(theme)
  .card({ variant: 'surface' })
  .use(withBorderPlugin({ width: 1, color: '#ddd' }))
  .build();
```

### Animations

Add animations to interactive components:

```typescript
const { withAnimationPlugin } = require('@/utils/theme/composer/plugins');

const animatedButton = createThemeComposer(theme)
  .button({ variant: 'contained' })
  .use(withAnimationPlugin({ duration: 200, easing: 'easeInOut' }))
  .build();
```

## Performance Tips

1. **Cache theme values**: Store composed themes at module or component level
2. **Avoid dynamic composition in renders**: Use `useMemo` or memoize at module level
3. **Reuse presets**: They're already optimized and tested
4. **Use custom composers sparingly**: Only when a preset doesn't fit your needs

## Type Safety

Always specify the generic type parameter when composing:

```typescript
// ✅ Good: Type-safe composition
const inputTheme = createThemeComposer<TextInputTheme>(theme)
  .textInput({ variant: 'primary' })
  .build();

// ❌ Avoid: Missing type parameter (loses type safety)
const inputTheme = createThemeComposer(theme)
  .textInput({ variant: 'primary' })
  .build();
```

## Testing

When testing components with themes:

```typescript
// Use presets in tests for consistency
describe('MyComponent', () => {
  it('should render with input theme', () => {
    const theme = themePresets.formInputStandard(testTheme);
    render(<MyComponent theme={theme} />);
    // ... assertions
  });
});
```

## Common Pitfalls

### 1. Forgetting to `.build()`

```typescript
// ❌ Wrong: Returns composer, not theme
const theme = createThemeComposer(theme).textInput({ variant: 'primary' });

// ✅ Correct
const theme = createThemeComposer(theme)
  .textInput({ variant: 'primary' })
  .build();
```

### 2. Mutating Returned Themes

```typescript
// ❌ Wrong: Themes are immutable, mutations won't persist
const theme = themePresets.cardStandard(theme);
theme.backgroundColor = '#fff';

// ✅ Correct: Create new theme
const theme = createThemeComposer(theme)
  .card({ variant: 'surface' })
  .build();
```

### 3. Building Themes in Loops

```typescript
// ❌ Wrong: Creates new theme on each iteration
items.map(() => themePresets.cardStandard(theme));

// ✅ Correct: Build once, reuse
const cardTheme = themePresets.cardStandard(theme);
items.map(() => cardTheme);
```

## Adding New Presets

When creating a new preset:

1. Add it to `presets.ts` with clear JSDoc comments
2. Follow the naming convention: `[component][Variant]`
3. Use existing presets as a base when possible
4. Add usage examples to this document

```typescript
/**
 * Custom alert card
 * Used for alert/warning messages
 */
alertCard: (theme: MD3Theme): CardTheme =>
  createThemeComposer<CardTheme>(theme)
    .card({ variant: 'warningContainer', elevated: true })
    .build(),
```

## Next Steps

- See [MIGRATION_EXAMPLES.md](./MIGRATION_EXAMPLES.md) for migrating existing code
- See [README.md](./README.md) for API reference
