# Theme Composer Migration Quick Reference

**Status:** In Progress (2/25 files migrated, 8%)  
**Current Phase:** Phase 1 Complete, Phase 2 Ready

## 30-Second Overview

We're migrating from manual theme colors in styles to centralized **Theme Composer presets**.

**What changed:**
- ❌ OLD: `color: theme.colors.primary` in every component
- ✅ NEW: `color: themePresets.sectionTitle(theme).color` (memoized)

**Why:**
- Consistent colors across app
- Automatic dark mode support
- Reduce code duplication
- Easier to maintain

## Copy-Paste Migration Template

```typescript
// 1. Add imports
import { useMemo } from 'react';
import { themePresets } from '../../utils/theme/composer/presets';

// 2. In component, after useTheme()
const theme = useTheme();
const sectionTitleStyle = useMemo(() => themePresets.sectionTitle(theme), [theme]);
const detailRowStyle = useMemo(() => themePresets.detailRow(theme), [theme]);

// 3. Apply in JSX (spread + color extraction)
<Text style={sectionTitleStyle}>Section Title</Text>
<View style={[detailRowStyle, styles.detailLayout]}>
  <Text style={[styles.label, { color: detailRowStyle.colors.label }]}>Label</Text>
  <Text style={[styles.value, { color: detailRowStyle.colors.value }]}>Value</Text>
</View>

// 4. Keep only layout styles
const getStyles = () => StyleSheet.create({
  detailLayout: {}, // flex layout from preset
  label: { flex: 1 },
  value: { flex: 2 },
});
```

## Available Presets

**Input Presets:**
- `formInputStandard` - Basic text input
- `formInputError` - Input with error state
- `formInputDisabled` - Disabled input
- `formInputErrorDisabled` - Error + disabled

**Card Presets:**
- `cardStandard` - Basic card
- `cardElevated` - Card with shadow
- `cardInfo` - Info-style card

**Dialog Presets:**
- `dialogStandard` - Basic modal
- `dialogFullScreen` - Full-screen modal

**NEW Container Presets:**
- `detailRow` - Label-value rows with colors
- `sectionTitle` - Section headers (18px bold)
- `descriptionContainer` - Text with label/value colors
- `evidenceContainer` - Evidence boxes with colors
- `chatContainer` - Chat history boxes
- `detailCard` - Elevated cards with colors

## 5-Minute Checklist

- [ ] Import `useMemo` and `themePresets`
- [ ] Create memoized preset hooks after `useTheme()`
- [ ] Replace `styles.*` theme colors with preset colors
- [ ] Use style arrays: `[presetStyle, { dynamicColor }]`
- [ ] Keep only layout styles in `getStyles()`
- [ ] Test dark mode (colors should auto-switch)
- [ ] Run `npx tsc --noEmit` ✅
- [ ] Review DisputeDetailsCard.tsx for pattern

## Common Mistakes

❌ **Forgetting memoization**
```typescript
// WRONG - recalculates every render
<Text style={themePresets.sectionTitle(theme)}>Title</Text>

// RIGHT - cached, updates on theme change
const titleStyle = useMemo(() => themePresets.sectionTitle(theme), [theme]);
<Text style={titleStyle}>Title</Text>
```

❌ **Not extracting colors**
```typescript
// WRONG - tries to spread color into Text
<Text style={detailRowStyle}>Text</Text>

// RIGHT - extracts color property
<Text style={[styles.text, { color: detailRowStyle.colors.value }]}>Text</Text>
```

❌ **Removing all styles**
```typescript
// WRONG - loses layout dimensions
const getStyles = () => StyleSheet.create({});

// RIGHT - keeps layout-specific styles
const getStyles = () => StyleSheet.create({
  label: { flex: 1 },      // Layout ratio
  value: { flex: 2 },      // Layout ratio
  container: { padding: 8 }, // Layout dimension
});
```

## Real Example

**DisputeDetailsCard.tsx** - See actual migration:

Before: 208 lines (including 22-line getStyles)  
After: 158 lines (including 8-line getStyles)

**Reduction:** ~50 lines of theme color code  
**Pattern:** sectionTitle, detailRow, evidenceContainer, chatContainer presets

## When to Migrate

✅ **Migrate if:**
- Component has >15 lines in getStyles()
- Many `theme.colors.*` references
- Not actively under heavy development
- You understand the pattern (see examples)

⏸️ **Skip if:**
- Active work on the component
- Complex conditional styling
- Waiting for design system finalization

## Need Help?

1. **Pattern help:** See `DisputeDetailsCard.tsx` (completed example)
2. **Preset help:** See `presets.ts` or `BEST_PRACTICES.md`
3. **Migration help:** See `MIGRATION_GUIDE.md`
4. **Examples:** See `MIGRATION_EXAMPLES.md`

## Files Ready to Migrate (Next Priority)

1. **DisputeEventHistory.tsx** - 16 styles, card pattern
2. **EscrowTransferCard.tsx** - 29 styles, card pattern
3. **ErrorIndicator.tsx** - 26 styles, simple
4. **InboxNotification.tsx** - 33 styles, notification
5. **PaymentQRCode.tsx** - 39 styles, layout

Estimated time: 30 min each

## Key Concepts

**Memoization**: Caches preset calculation until theme changes
```typescript
const style = useMemo(() => themePresets.preset(theme), [theme]);
```

**Color Extraction**: Gets color from preset object
```typescript
{ color: presetStyle.colors.label }
```

**Style Merging**: Combine preset + layout styles
```typescript
style={[presetStyle, layoutStyles]}
```

**Automatic Dark Mode**: Preset colors automatically switch with theme
```typescript
// In dark mode, colors automatically invert via presets
// No additional code needed
```

---

**Last Updated:** 2026-01-10  
**Completion:** 8% (2/25 files)  
**Next Step:** Execute Phase 2 (5 more files)

See `MIGRATION_GUIDE.md` for full details.
