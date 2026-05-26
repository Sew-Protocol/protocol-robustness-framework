# Theme Composer

Functional, fluent API for building theme configurations.

## Quick Start

```typescript
import { createThemeComposer } from '@/utils/theme';

const inputTheme = createThemeComposer(theme)
  .textInput({ variant: 'primary', size: 'large' })
  .withError()
  .build();
```

## Features

- ✅ **Functional**: Uses closures, no classes
- ✅ **Immutable**: Each method returns a new composer
- ✅ **Chainable**: Fluent API for building themes
- ✅ **Type-safe**: Full TypeScript support
- ✅ **Composable**: Combine multiple modifiers

## Usage Examples

### Basic Usage

```typescript
const inputTheme = createThemeComposer(theme)
  .textInput({ variant: 'primary' })
  .withError()
  .build();
```

### Conditional Composition

```typescript
const inputTheme = createThemeComposer(theme)
  .textInput({ variant: 'primary' })
  .withConditional(hasError, (c) => c.withError())
  .withConditional(isDisabled, (c) => c.withDisabled())
  .build();
```

### Using Presets

```typescript
import { themePresets } from '@/utils/theme/composer/presets';

// Input presets
const inputTheme = themePresets.primaryErrorInput(theme);
const formInput = themePresets.formInputStandard(theme);

// Button presets
const buttonTheme = themePresets.disabledButton(theme);

// Card presets
const standardCard = themePresets.cardStandard(theme);
const elevatedCard = themePresets.cardElevated(theme, 6);
const infoCard = themePresets.cardInfo(theme);

// Dialog presets
const dialog = themePresets.dialogStandard(theme);
const fullScreenDialog = themePresets.dialogFullScreen(theme);
```

### With Plugins

```typescript
import { withShadowPlugin } from '@/utils/theme/composer/plugins';

const cardTheme = createThemeComposer(theme)
  .card({ variant: 'elevated' })
  .use(withShadowPlugin({ elevation: 8 }))
  .build();
```

### Reactive Hook

```typescript
import { useThemeComposer } from '@/utils/theme';

function MyComponent() {
  const composer = useThemeComposer();
  
  const inputTheme = useMemo(
    () => composer
      .textInput({ variant: 'primary' })
      .withError()
      .build(),
    [composer]
  );
  
  return <TextInput theme={inputTheme} />;
}
```

## API Reference

See the main theme documentation for full API reference.

