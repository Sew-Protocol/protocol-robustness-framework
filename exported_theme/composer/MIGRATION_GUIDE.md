# Style Migration to Theme Composer Guide

This guide outlines how to migrate existing style definitions to use the Theme Composer presets.

## Status: MIGRATION IN PROGRESS

✅ Completed:
- DisputeDetailsCard.tsx - MIGRATED
- DisputeTransactionCard.tsx - MIGRATED
- Container presets added (detailRow, sectionTitle, descriptionContainer, evidenceContainer, chatContainer, detailCard)

## Quick Wins (Easy Migrations)

These are the quickest migrations with maximum benefit:

### 1. Components using TextInput with form patterns

**Before:**
```typescript
const inputTheme = {
  colors: {
    primary: theme.colors.primary,
    onSurface: theme.colors.onSurface,
  },
};
return <TextInput theme={inputTheme} />;
```

**After:**
```typescript
const inputTheme = useMemo(
  () => themePresets.formInputStandard(theme),
  [theme]
);
return <TextInput theme={inputTheme} />;
```

**Files to migrate:**
- Components with `<TextInput />` that create inline themes
- Forms with multiple input fields

### 2. Components using Card elements

**Before:**
```typescript
const styles = StyleSheet.create({
  card: {
    marginBottom: 16,
    elevation: 2,
    backgroundColor: theme.colors.surface,
  },
});
return <Card style={styles.card}>...</Card>;
```

**After:**
```typescript
const cardTheme = useMemo(
  () => themePresets.cardElevated(theme),
  [theme]
);
return <Card theme={cardTheme}>...</Card>;
```

**Files to migrate:**
- ✅ `DisputeDetailsCard.tsx` - MIGRATED (uses detailRow, sectionTitle, evidenceContainer, chatContainer presets)
- ✅ `DisputeTransactionCard.tsx` - MIGRATED (uses detailRow, sectionTitle, detailCard presets)
- `DisputeEventHistory.tsx` - READY (simple card pattern)
- `EscrowTransferCard.tsx` - READY (elevated card pattern)

### 3. Components using Dialog

**Before:**
```typescript
const dialogTheme = {
  backgroundColor: theme.colors.surface,
};
return <Dialog theme={dialogTheme}>...</Dialog>;
```

**After:**
```typescript
const dialogTheme = useMemo(
  () => themePresets.dialogStandard(theme),
  [theme]
);
return <Dialog theme={dialogTheme}>...</Dialog>;
```

**Files to migrate:**
- Any component with `<Dialog />` that manually sets backgroundColor

### 4. Detail rows and sections

Many components have repeating patterns like:

```typescript
// OLD: Manual styling
const styles = {
  detailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 16,
    color: theme.colors.onSurface,
  },
};
```

**After:**
```typescript
// NEW: Use presets
const detailRowStyle = useMemo(() => themePresets.detailRow(theme), [theme]);
const titleStyle = useMemo(() => themePresets.sectionTitle(theme), [theme]);
```

## Migration Pattern (Recommended)

After migrating `DisputeDetailsCard.tsx` and `DisputeTransactionCard.tsx`, the pattern is:

1. **Import presets and useMemo**
   ```typescript
   import { useMemo } from 'react';
   import { themePresets } from '../../utils/theme/composer/presets';
   ```

2. **Memoize preset styles in component**
   ```typescript
   const theme = useTheme();
   const sectionTitleStyle = useMemo(() => themePresets.sectionTitle(theme), [theme]);
   const detailRowStyle = useMemo(() => themePresets.detailRow(theme), [theme]);
   ```

3. **Apply presets with style spreading**
   ```typescript
   <Text style={sectionTitleStyle}>Title</Text>
   <View style={[detailRowStyle, styles.detailRowFlex]}>
     <Text style={[styles.label, { color: detailRowStyle.colors.label }]}>Label</Text>
   </View>
   ```

4. **Keep layout-specific styles only**
   ```typescript
   const getStyles = () => StyleSheet.create({
     detailRowFlex: {},  // Layout flex handled by preset
     label: { flex: 1 }, // Only flex ratios, not colors
     value: { flex: 2 }, // Layout proportions
   });
   ```

## Migration Priority

### Priority 1 (Highest Impact): 
- Component count: > 3 files using similar patterns
- Style reusability: HIGH
- Effort: LOW
- Status: ONGOING

**Completed:**
- ✅ DisputeDetailsCard - Used sectionTitle, detailRow, descriptionContainer, evidenceContainer, chatContainer presets
- ✅ DisputeTransactionCard - Used sectionTitle, detailRow, detailCard presets

**Next:** 
- DisputeEventHistory.tsx (ready)
- EscrowTransferCard.tsx (ready)
- ErrorIndicator.tsx (ready)
- InboxNotification.tsx (ready)

### Priority 2 (Medium Impact):
- Component count: 1-2 files
- Style reusability: MEDIUM
- Effort: MEDIUM

**Candidates:**
- Dialog components (using dialogStandard preset)
- Custom form components (using formInputStandard preset)
- Notification containers

### Priority 3 (Low Priority):
- Component count: < 1
- Style reusability: LOW
- Effort: HIGH

**Candidates:**
- Large screens with many unique styles (dispute-details/[id].tsx)
- Components with complex responsive styling (PaymentInvoice.tsx)

## Execution Steps

### For each component:

1. **Identify reusable styles**
   ```bash
   grep -n "styles\." components/MyComponent.tsx | head -20
   ```

2. **Check if presets exist**
   - Look in `presets.ts` for matching patterns
   - Add new presets if needed

3. **Replace inline themes with memoized presets**
   ```typescript
   // OLD
   const styles = getStyles(theme);
   const myTheme = { ...styles };
   
   // NEW
   const myTheme = useMemo(() => themePresets.myPreset(theme), [theme]);
   ```

4. **Extract color-only references**
   ```typescript
   // OLD
   color: theme.colors.primary
   
   // NEW
   color: themePresets.sectionTitle(theme).color
   ```

5. **Test thoroughly**
   - Visual consistency
   - Theme switching
   - Responsive behavior
   - TypeScript: `npx tsc --noEmit`

## New Presets Added

Based on actual migration needs, these presets were added:

```typescript
// Detail row container (flex row with spacing)
detailRow: (theme) => ({
  flexDirection: 'row',
  justifyContent: 'space-between',
  alignItems: 'flex-start',
  marginBottom: 12,
  colors: { label, value },
})

// Section title styling
sectionTitle: (theme) => ({
  fontSize: 18,
  fontWeight: 'bold',
  marginBottom: 16,
  color: theme.colors.onSurface,
})

// Description/text container
descriptionContainer: (theme) => ({
  marginBottom: 16,
  paddingHorizontal: 12,
  colors: { label, text },
})

// Evidence/attachment container
evidenceContainer: (theme) => ({
  marginBottom: 16,
  padding: 12,
  backgroundColor: theme.colors.surfaceVariant,
  borderRadius: 8,
  colors: { label, text, type },
})

// Chat history container
chatContainer: (theme) => ({
  marginBottom: 16,
  padding: 12,
  backgroundColor: theme.colors.surfaceVariant,
  borderRadius: 8,
  colors: { label, text },
})

// Detail card with evidence
detailCard: (theme) => ({
  marginBottom: 12,
  elevation: 2,
  backgroundColor: theme.colors.surface,
  colors: { title, label, value },
})
```

## Checklist for Each Migration

- [ ] Identified all styleable elements
- [ ] Found or created matching presets
- [ ] Added `useMemo` for memoization
- [ ] Tested visual appearance
- [ ] Tested dark mode
- [ ] Tested responsive sizes
- [ ] Reduced style definition lines
- [ ] Ran TypeScript check (`npx tsc --noEmit`)
- [ ] Ran linting (`npm run lint`)

## Common Pitfalls

1. **Forgetting useMemo**
   - Creates new theme object on every render
   - Causes unnecessary style recalculations
   - ✅ Solution: Wrap with `useMemo(() => themePresets.preset(theme), [theme])`

2. **Removing all inline styles**
   - Some styles are layout-specific (width, height, flex ratios)
   - Only extract theme colors
   - ✅ Solution: Keep layout styles, extract color and spacing

3. **Not checking dark mode**
   - Always test with dark theme
   - Presets handle color mappings correctly
   - ✅ Solution: Toggle theme in dev tools

4. **Mixing preset and manual styles**
   - Use style array spreading
   - Override preset colors with inline dynamic colors
   - ✅ Solution: `<Text style={[titleStyle, { color: dynamicColor }]}>Text</Text>`

5. **Forgetting to import presets**
   - TypeScript will catch this
   - ✅ Solution: `import { themePresets } from '../../utils/theme/composer/presets'`

## Getting Help

- See `BEST_PRACTICES.md` for patterns and guidance
- See `MIGRATION_EXAMPLES.md` for before/after code examples
- See `README.md` for API reference
- Check migrated components for patterns:
  - DisputeDetailsCard.tsx
  - DisputeTransactionCard.tsx

## Success Metrics

After migration, you should see:

- ✅ Reduced style definition lines per component
- ✅ Consistent theming across components
- ✅ Easier dark mode support
- ✅ Centralized color management
- ✅ Faster component development (reuse presets)

## Migration Progress

```
Total files with heavy styling: ~25
High-priority candidates: 7
Medium-priority candidates: 4
Low-priority candidates: 14

Progress:
- Completed: 2 files (DisputeDetailsCard, DisputeTransactionCard)
- Ready: 5 files (DisputeEventHistory, EscrowTransferCard, ErrorIndicator, InboxNotification, etc.)
- In Progress: 0 files
- Estimated Remaining: 18 files

Completion Rate: 8% (2/25)
```

## Files Ready for Migration (Ordered by Priority)

### High Priority (Easy wins) - READY
1. ✅ `components/dispute/DisputeDetailsCard.tsx` - COMPLETED
2. ✅ `components/dispute/DisputeTransactionCard.tsx` - COMPLETED
3. `components/dispute/DisputeEventHistory.tsx` - 16 styles, card pattern
4. `components/escrow/EscrowTransferCard.tsx` - 29 styles, card pattern
5. `components/ErrorIndicator.tsx` - 26 styles, simple pattern
6. `components/InboxNotification.tsx` - 33 styles, notification pattern

### Medium Priority 
7. `components/WalletAddressManager.tsx` - 40 styles, mixed patterns
8. `components/PaymentQRCode.tsx` - 39 styles, layout focused
9. `components/SignatureCapture.tsx` - 31 styles, mixed patterns
10. `components/LocationPicker.tsx` - 31 styles, modal pattern

### Lower Priority (Complex screens)
11. `app/dispute-details/[id].tsx` - 110+ styles, large screen
12. `components/PaymentInvoice.tsx` - 58 styles, complex layout
13. `components/dispute/DisputeEventHistory.tsx` - Already well-structured
14. Other components with < 20 styles each

## Notes

- Keep migrations atomic (one file per commit)
- Test after each migration
- Document new presets in README.md
- Collect feedback for improvement
- Share migrated components as examples for team

## Technical Details

**Why memoization matters:**
- Without `useMemo`, presets are recalculated every render
- With `useMemo`, presets are cached until theme changes
- Dependency array `[theme]` ensures updates when theme switches (dark mode)

**Why style arrays work:**
- React Native supports style array spreading
- Later styles override earlier ones in array
- Allows preset + dynamic overrides: `[presetStyle, { color: dynamic }]`

**Color extraction pattern:**
- Presets return objects with `colors` property
- Access dynamic colors: `detailRowStyle.colors.label`
- Apply with inline styles: `{ color: detailRowStyle.colors.label }`


These are the quickest migrations with maximum benefit:

### 1. Components using TextInput with form patterns

**Before:**
```typescript
const inputTheme = {
  colors: {
    primary: theme.colors.primary,
    onSurface: theme.colors.onSurface,
  },
};
return <TextInput theme={inputTheme} />;
```

**After:**
```typescript
const inputTheme = useMemo(
  () => themePresets.formInputStandard(theme),
  [theme]
);
return <TextInput theme={inputTheme} />;
```

**Files to migrate:**
- Components with `<TextInput />` that create inline themes
- Forms with multiple input fields

### 2. Components using Card elements

**Before:**
```typescript
const styles = StyleSheet.create({
  card: {
    marginBottom: 16,
    elevation: 2,
    backgroundColor: theme.colors.surface,
  },
});
return <Card style={styles.card}>...</Card>;
```

**After:**
```typescript
const cardTheme = useMemo(
  () => themePresets.cardElevated(theme),
  [theme]
);
return <Card theme={cardTheme}>...</Card>;
```

**Files to migrate:**
- `DisputeDetailsCard.tsx` - Uses `card` style
- `DisputeEventHistory.tsx` - Uses card-like containers
- `DisputeTransactionCard.tsx` - Uses elevated cards

### 3. Components using Dialog

**Before:**
```typescript
const dialogTheme = {
  backgroundColor: theme.colors.surface,
};
return <Dialog theme={dialogTheme}>...</Dialog>;
```

**After:**
```typescript
const dialogTheme = useMemo(
  () => themePresets.dialogStandard(theme),
  [theme]
);
return <Dialog theme={dialogTheme}>...</Dialog>;
```

**Files to migrate:**
- Any component with `<Dialog />` that manually sets backgroundColor

### 4. Detail rows and sections

Many components have repeating patterns like:

```typescript
// OLD: Manual styling
const styles = {
  detailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 16,
    color: theme.colors.onSurface,
  },
};
```

**After:**
```typescript
// NEW: Use presets
const detailRowStyle = useMemo(() => themePresets.detailRow(theme), [theme]);
const titleStyle = useMemo(() => themePresets.sectionTitle(theme), [theme]);
```

## Migration Priority

### Priority 1 (Highest Impact): 
- Component count: > 3 files
- Style reusability: HIGH
- Effort: LOW

**Candidates:**
- Detail row styles (used in 10+ components)
- Card styles (used in 5+ components)
- Input themes (used in 5+ components)

### Priority 2 (Medium Impact):
- Component count: 1-2 files
- Style reusability: MEDIUM
- Effort: MEDIUM

**Candidates:**
- Dialog components
- Custom form components
- Evidence/attachment containers

### Priority 3 (Low Priority):
- Component count: < 1
- Style reusability: LOW
- Effort: HIGH

**Candidates:**
- Large screens with many unique styles
- Components with complex responsive styling

## Execution Steps

### For each component:

1. **Identify reusable styles**
   ```bash
   grep -n "styles\." components/MyComponent.tsx | head -20
   ```

2. **Check if presets exist**
   - Look in `presets.ts` for matching patterns
   - Add new presets if needed

3. **Replace inline themes with memoized presets**
   ```typescript
   // OLD
   const styles = getStyles(theme);
   const myTheme = { ...styles };
   
   // NEW
   const myTheme = useMemo(() => themePresets.myPreset(theme), [theme]);
   ```

4. **Extract color-only references**
   ```typescript
   // OLD
   color: theme.colors.primary
   
   // NEW
   color: themePresets.sectionTitle(theme).color
   ```

5. **Test thoroughly**
   - Visual consistency
   - Theme switching
   - Responsive behavior

## New Presets to Add

Based on migration analysis, add these presets for common patterns:

```typescript
// Stripe container
stripeContainer: (theme) => ({
  backgroundColor: theme.colors.surfaceVariant,
  borderRadius: 8,
  padding: 12,
})

// Amount display
amountText: (theme) => ({
  fontWeight: 'bold',
  color: theme.colors.primary,
  fontSize: 18,
})

// Status chip
statusChip: (theme) => ({
  backgroundColor: theme.colors.primary + '20',
})
```

## Checklist for Each Migration

- [ ] Identified all styleable elements
- [ ] Found or created matching presets
- [ ] Added memoization with `useMemo`
- [ ] Tested visual appearance
- [ ] Tested dark mode
- [ ] Tested responsive sizes
- [ ] Updated documentation
- [ ] Ran TypeScript check (`npx tsc --noEmit`)
- [ ] Ran linting (`npm run lint`)

## Common Pitfalls

1. **Forgetting useMemo**
   - Creates new theme object on every render
   - Causes unnecessary style recalculations

2. **Removing all inline styles**
   - Some styles are layout-specific (width, height, flex)
   - Keep those, only extract theme colors

3. **Not checking dark mode**
   - Always test with dark theme
   - Presets handle color mappings correctly

4. **Mixing preset and manual styles**
   - Use `...themePresets.preset(theme)` to merge
   - Avoid spreading both preset and manual objects

## Getting Help

- See `BEST_PRACTICES.md` for patterns and guidance
- See `MIGRATION_EXAMPLES.md` for before/after code examples
- See `README.md` for API reference

## Success Metrics

After migration, you should see:

- ✅ Reduced style definition lines per component
- ✅ Consistent theming across components
- ✅ Easier dark mode support
- ✅ Centralized color management
- ✅ Faster component development (reuse presets)

## Files Ready for Migration (Ordered by Priority)

### High Priority (Easy wins)
1. `components/dispute/DisputeDetailsCard.tsx` - Card-based, 22 styles
2. `components/dispute/DisputeEventHistory.tsx` - Card-based, 16 styles
3. `components/dispute/DisputeTransactionCard.tsx` - Card-based, 27 styles
4. `components/escrow/EscrowTransferCard.tsx` - Card-based, 29 styles

### Medium Priority 
5. `components/WalletAddressManager.tsx` - Mixed styles, 40 styles
6. `components/ErrorIndicator.tsx` - Simple styling, 26 styles
7. `components/InboxNotification.tsx` - Notification pattern, 33 styles

### Lower Priority (Complex screens)
8. `app/dispute-details/[id].tsx` - Large screen, 110+ styles
9. `components/PaymentInvoice.tsx` - Complex layout, 58 styles

## Notes

- Keep migrations atomic (one file per commit)
- Test after each migration
- Document new presets in README.md
- Collect feedback for improvement
