# Theme Composer Migration Examples

Before/after examples for migrating to Theme Composer patterns.

## Input Theme Migration

### Before: Inline Theme Creation

```typescript
import { TextInput } from 'react-native-paper';
import { MD3Theme } from 'react-native-paper';

function LoginForm({ theme }: { theme: MD3Theme }) {
  const inputTheme = {
    colors: {
      primary: theme.colors.primary,
      onSurface: theme.colors.onSurface,
      error: theme.colors.error,
      disabled: theme.colors.surfaceDisabled,
    },
    roundness: 8,
  };

  return <TextInput theme={inputTheme} label="Email" />;
}
```

### After: Using Presets

```typescript
import { TextInput } from 'react-native-paper';
import { MD3Theme } from 'react-native-paper';
import { themePresets } from '@/utils/theme/composer/presets';
import { useMemo } from 'react';

function LoginForm({ theme }: { theme: MD3Theme }) {
  const inputTheme = useMemo(() => 
    themePresets.formInputStandard(theme),
    [theme]
  );

  return <TextInput theme={inputTheme} label="Email" />;
}
```

## Card Theme Migration

### Before: Manual Elevation and Styling

```typescript
import { Card } from 'react-native-paper';
import { StyleSheet, View } from 'react-native';
import { MD3Theme } from 'react-native-paper';

function ProductCard({ theme, product }: { theme: MD3Theme; product: any }) {
  const styles = StyleSheet.create({
    card: {
      backgroundColor: theme.colors.surface,
      elevation: 2,
      shadowColor: '#000',
      shadowOffset: { width: 0, height: 2 },
      shadowOpacity: 0.25,
      shadowRadius: 3.84,
    },
  });

  return (
    <Card style={styles.card}>
      <Card.Content>
        <Text>{product.name}</Text>
      </Card.Content>
    </Card>
  );
}
```

### After: Using Card Presets

```typescript
import { Card } from 'react-native-paper';
import { Text } from 'react-native-paper';
import { MD3Theme } from 'react-native-paper';
import { themePresets } from '@/utils/theme/composer/presets';
import { useMemo } from 'react';

function ProductCard({ theme, product }: { theme: MD3Theme; product: any }) {
  const cardTheme = useMemo(() =>
    themePresets.cardElevated(theme),
    [theme]
  );

  return (
    <Card theme={cardTheme}>
      <Card.Content>
        <Text>{product.name}</Text>
      </Card.Content>
    </Card>
  );
}
```

## Dialog Theme Migration

### Before: Custom Dialog Styling

```typescript
import { Dialog, Portal } from 'react-native-paper';
import { MD3Theme } from 'react-native-paper';
import { StyleSheet } from 'react-native';

function ConfirmDialog({
  theme,
  visible,
  onDismiss,
}: {
  theme: MD3Theme;
  visible: boolean;
  onDismiss: () => void;
}) {
  const styles = StyleSheet.create({
    dialog: {
      backgroundColor: theme.colors.surface,
    },
  });

  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss} style={styles.dialog}>
        <Dialog.Title>Confirm Action</Dialog.Title>
        <Dialog.Content>
          <Text>Are you sure?</Text>
        </Dialog.Content>
      </Dialog>
    </Portal>
  );
}
```

### After: Using Dialog Presets

```typescript
import { Dialog, Portal, Text } from 'react-native-paper';
import { MD3Theme } from 'react-native-paper';
import { themePresets } from '@/utils/theme/composer/presets';
import { useMemo } from 'react';

function ConfirmDialog({
  theme,
  visible,
  onDismiss,
}: {
  theme: MD3Theme;
  visible: boolean;
  onDismiss: () => void;
}) {
  const dialogTheme = useMemo(() =>
    themePresets.dialogStandard(theme),
    [theme]
  );

  return (
    <Portal>
      <Dialog visible={visible} onDismiss={onDismiss} theme={dialogTheme}>
        <Dialog.Title>Confirm Action</Dialog.Title>
        <Dialog.Content>
          <Text>Are you sure?</Text>
        </Dialog.Content>
      </Dialog>
    </Portal>
  );
}
```

## Conditional Theme Migration

### Before: Multiple Theme Objects

```typescript
import { TextInput } from 'react-native-paper';
import { MD3Theme } from 'react-native-paper';
import { useState } from 'react';

function FormField({ theme, label }: { theme: MD3Theme; label: string }) {
  const [value, setValue] = useState('');
  const [error, setError] = useState('');
  const [disabled, setDisabled] = useState(false);

  let inputTheme;
  if (disabled && error) {
    inputTheme = {
      colors: {
        primary: theme.colors.disabled,
        error: theme.colors.error,
        onSurface: theme.colors.onSurfaceDisabled,
      },
    };
  } else if (error) {
    inputTheme = {
      colors: {
        primary: theme.colors.error,
        onSurface: theme.colors.onSurface,
      },
    };
  } else if (disabled) {
    inputTheme = {
      colors: {
        primary: theme.colors.disabled,
        onSurface: theme.colors.onSurfaceDisabled,
      },
    };
  } else {
    inputTheme = {
      colors: {
        primary: theme.colors.primary,
        onSurface: theme.colors.onSurface,
      },
    };
  }

  return <TextInput theme={inputTheme} label={label} value={value} />;
}
```

### After: Using Conditional Composition

```typescript
import { TextInput } from 'react-native-paper';
import { MD3Theme } from 'react-native-paper';
import { useState, useMemo } from 'react';
import { createThemeComposer } from '@/utils/theme/composer';

function FormField({ theme, label }: { theme: MD3Theme; label: string }) {
  const [value, setValue] = useState('');
  const [error, setError] = useState('');
  const [disabled, setDisabled] = useState(false);

  const inputTheme = useMemo(
    () =>
      createThemeComposer(theme)
        .textInput({ variant: 'default' })
        .withConditional(error, (c) => c.withError())
        .withConditional(disabled, (c) => c.withDisabled())
        .build(),
    [theme, error, disabled]
  );

  return <TextInput theme={inputTheme} label={label} value={value} />;
}
```

## Component with Multiple Theme Applications

### Before: Many Manual Themes

```typescript
import { Card, TextInput, Button } from 'react-native-paper';
import { View, StyleSheet } from 'react-native';
import { MD3Theme } from 'react-native-paper';

function UserProfileCard({ theme, user }: { theme: MD3Theme; user: any }) {
  const styles = StyleSheet.create({
    card: {
      backgroundColor: theme.colors.surface,
      elevation: 2,
    },
    input: {
      backgroundColor: theme.colors.surfaceVariant,
    },
    button: {
      backgroundColor: theme.colors.primary,
    },
  });

  const cardTheme = {
    colors: { surface: theme.colors.surface },
  };

  const inputTheme = {
    colors: {
      primary: theme.colors.primary,
      onSurface: theme.colors.onSurface,
    },
  };

  const buttonTheme = {
    colors: { primary: theme.colors.primary },
  };

  return (
    <Card style={styles.card} theme={cardTheme}>
      <Card.Content>
        <TextInput
          label="Name"
          theme={inputTheme}
          defaultValue={user.name}
          style={styles.input}
        />
        <Button theme={buttonTheme} style={styles.button}>
          Save
        </Button>
      </Card.Content>
    </Card>
  );
}
```

### After: Using Presets

```typescript
import { Card, TextInput, Button } from 'react-native-paper';
import { View } from 'react-native';
import { MD3Theme } from 'react-native-paper';
import { themePresets } from '@/utils/theme/composer/presets';
import { useMemo } from 'react';

function UserProfileCard({ theme, user }: { theme: MD3Theme; user: any }) {
  const cardTheme = useMemo(
    () => themePresets.cardElevated(theme),
    [theme]
  );
  const inputTheme = useMemo(
    () => themePresets.formInputStandard(theme),
    [theme]
  );
  const buttonTheme = useMemo(
    () => themePresets.disabledButton(theme),
    [theme]
  );

  return (
    <Card theme={cardTheme}>
      <Card.Content>
        <TextInput
          label="Name"
          theme={inputTheme}
          defaultValue={user.name}
        />
        <Button theme={buttonTheme}>Save</Button>
      </Card.Content>
    </Card>
  );
}
```

## Custom Plugin Usage Migration

### Before: Custom Styling for Shadows

```typescript
import { Card } from 'react-native-paper';
import { View, StyleSheet } from 'react-native';
import { MD3Theme } from 'react-native-paper';

function ElevatedCard({ theme, children }: { theme: MD3Theme; children: any }) {
  const styles = StyleSheet.create({
    card: {
      backgroundColor: theme.colors.surface,
      elevation: 8,
      shadowColor: '#000',
      shadowOffset: { width: 0, height: 4 },
      shadowOpacity: 0.3,
      shadowRadius: 4.65,
      borderRadius: 12,
    },
  });

  return <Card style={styles.card}>{children}</Card>;
}
```

### After: Using Shadow Plugin

```typescript
import { Card } from 'react-native-paper';
import { MD3Theme } from 'react-native-paper';
import { createThemeComposer, withShadowPlugin } from '@/utils/theme/composer';
import { useMemo } from 'react';

function ElevatedCard({ theme, children }: { theme: MD3Theme; children: any }) {
  const cardTheme = useMemo(
    () =>
      createThemeComposer(theme)
        .card({ variant: 'elevated' })
        .use(withShadowPlugin({ elevation: 8 }))
        .build(),
    [theme]
  );

  return <Card theme={cardTheme}>{children}</Card>;
}
```

## Key Migration Steps

1. **Identify repetitive theme objects** - Look for patterns in manual styling
2. **Check if a preset exists** - Most common patterns are already covered
3. **Replace with preset** - Use `themePresets.[preset](theme)`
4. **Add memoization** - Wrap in `useMemo` to avoid recalculations
5. **Test thoroughly** - Ensure visual and functional parity

## Benefits of Migration

| Aspect | Before | After |
|--------|--------|-------|
| Code size | Many manual theme objects | Concise preset calls |
| Maintenance | Each component manages styles | Centralized presets |
| Consistency | Potential for inconsistency | Guaranteed consistency |
| Reusability | Duplicated across components | Single definition |
| Type safety | Often implicit | Explicit and checked |
| Performance | Recalculated on each render | Memoized |

## Questions?

See [BEST_PRACTICES.md](./BEST_PRACTICES.md) for more guidance, or [README.md](./README.md) for API reference.
