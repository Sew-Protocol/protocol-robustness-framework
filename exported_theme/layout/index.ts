/**
 * Layout Utilities
 * 
 * Provides common layout style patterns for consistent component layouts
 */

import { StyleSheet, type ViewStyle } from 'react-native';
import { getSpacing } from '../core/spacing';

/**
 * Common layout styles
 * 
 * These are static styles that don't depend on theme
 */
export const layoutStyles = StyleSheet.create({
  row: {
    flexDirection: 'row',
  } as ViewStyle,

  rowCentered: {
    flexDirection: 'row',
    alignItems: 'center',
  } as ViewStyle,

  rowSpaceBetween: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  } as ViewStyle,

  column: {
    flexDirection: 'column',
  } as ViewStyle,

  columnCentered: {
    flexDirection: 'column',
    alignItems: 'center',
  } as ViewStyle,

  fullWidth: {
    width: '100%',
  } as ViewStyle,

  flex1: {
    flex: 1,
  } as ViewStyle,

  // Common spacing patterns
  marginTopSm: {
    marginTop: getSpacing('sm'),
  } as ViewStyle,

  marginTopMd: {
    marginTop: getSpacing('md'),
  } as ViewStyle,

  marginBottomSm: {
    marginBottom: getSpacing('sm'),
  } as ViewStyle,

  marginBottomMd: {
    marginBottom: getSpacing('md'),
  } as ViewStyle,

  // Gap patterns
  gapSm: {
    gap: getSpacing('sm'),
  } as ViewStyle,

  gapMd: {
    gap: getSpacing('md'),
  } as ViewStyle,
});

/**
 * Create layout styles with spacing
 * 
 * @param config - Layout configuration
 * @returns ViewStyle object
 */
export function createLayoutStyle(config: {
  direction?: 'row' | 'column';
  align?: 'center' | 'start' | 'end' | 'stretch';
  justify?: 'start' | 'center' | 'end' | 'space-between' | 'space-around';
  gap?: 'xs' | 'sm' | 'md' | 'lg';
  padding?: 'xs' | 'sm' | 'md' | 'lg';
  margin?: 'xs' | 'sm' | 'md' | 'lg';
  fullWidth?: boolean;
  flex?: number;
}): ViewStyle {
  const {
    direction = 'column',
    align,
    justify,
    gap,
    padding,
    margin,
    fullWidth = false,
    flex,
  } = config;

  const style: ViewStyle = {
    flexDirection: direction,
  };

  if (align) {
    style.alignItems = align === 'center' ? 'center' :
                      align === 'start' ? 'flex-start' :
                      align === 'end' ? 'flex-end' :
                      'stretch';
  }

  if (justify) {
    style.justifyContent = justify === 'start' ? 'flex-start' :
                          justify === 'center' ? 'center' :
                          justify === 'end' ? 'flex-end' :
                          justify === 'space-between' ? 'space-between' :
                          'space-around';
  }

  if (gap) {
    style.gap = getSpacing(gap);
  }

  if (padding) {
    style.padding = getSpacing(padding);
  }

  if (margin) {
    style.margin = getSpacing(margin);
  }

  if (fullWidth) {
    style.width = '100%';
  }

  if (flex !== undefined) {
    style.flex = flex;
  }

  return style;
}













































































