import {
  Score,
  WidgetRegistry,
  defineWidget,
  type WidgetDefinition,
  type WidgetInput,
  type WidgetModule,
} from '@/providers/widgetRegistry'
import { DisplayMode, widgetConfigurationSchema } from '@/providers/widgetRegistry/configuration'
import { GraphDb } from '@/stores/graph/graphDatabase'
import { Ast } from '@/util/ast'
import { ApplicationKind, ArgumentPlaceholder } from '@/util/callTree'
import { describe, expect, test } from 'vitest'
import { defineComponent } from 'vue'

describe('WidgetRegistry', () => {
  function makeMockWidget<T extends WidgetInput>(
    name: string,
    widgetDefinition: WidgetDefinition<T>,
  ): WidgetModule<T> {
    return {
      default: defineComponent({ name }),
      widgetDefinition,
    }
  }

  const widgetA = makeMockWidget(
    'A',
    defineWidget(Ast.Ast, {
      priority: 1,
    }),
  )

  const widgetB = makeMockWidget(
    'B',
    defineWidget(ArgumentPlaceholder, {
      priority: 2,
    }),
  )

  const widgetC = makeMockWidget(
    'C',
    defineWidget((input: WidgetInput): input is WidgetInput => true, {
      priority: 10,
      score: Score.Good,
    }),
  )

  const widgetD = makeMockWidget(
    'D',
    defineWidget(Ast.Ast, {
      priority: 20,
      score: (props) => (props.input.code() === '_' ? Score.Perfect : Score.Mismatch),
    }),
  )

  const someAst = Ast.parse('foo')
  const blankAst = Ast.parse('_')
  const somePlaceholder = new ArgumentPlaceholder(
    0,
    {
      name: 'foo',
      type: 'Any',
      isSuspended: false,
      hasDefault: false,
    },
    ApplicationKind.Prefix,
  )

  const mockGraphDb = GraphDb.Mock()
  const registry = new WidgetRegistry(mockGraphDb)
  registry.registerWidgetModule(widgetA)
  registry.registerWidgetModule(widgetB)
  registry.registerWidgetModule(widgetC)
  registry.registerWidgetModule(widgetD)

  test('selects a widget based on the input type', () => {
    const forAst = registry.select({ input: someAst, config: undefined, nesting: 0 })
    const forArg = registry.select({ input: somePlaceholder, config: undefined, nesting: 0 })
    expect(forAst).toStrictEqual(widgetA)
    expect(forArg).toStrictEqual(widgetB)
  })

  test('selects a widget outside of the excluded set', () => {
    const forAst = registry.select(
      { input: someAst, config: undefined, nesting: 0 },
      new Set([widgetA.default]),
    )
    const forArg = registry.select(
      { input: somePlaceholder, config: undefined, nesting: 0 },
      new Set([widgetB.default]),
    )
    expect(forAst).toStrictEqual(widgetC)
    expect(forArg).toStrictEqual(widgetC)
  })

  test('returns undefined when all options are exhausted', () => {
    const selected = registry.select(
      { input: someAst, config: undefined, nesting: 0 },
      new Set([widgetA.default, widgetC.default]),
    )
    expect(selected).to.be.undefined
  })

  test('prefers low priority perfect over good high priority', () => {
    const selectedFirst = registry.select(
      { input: blankAst, config: undefined, nesting: 0 },
      new Set([widgetA.default]),
    )
    const selectedNext = registry.select(
      { input: blankAst, config: undefined, nesting: 0 },
      new Set([widgetA.default, widgetD.default]),
    )
    expect(selectedFirst).toStrictEqual(widgetD)
    expect(selectedNext).toStrictEqual(widgetC)
  })
})

/* eslint-disable camelcase */
describe('Engine-provided configuration', () => {
  const singleChoiceData = [
    'range',
    {
      type: 'Widget',
      constructor: 'Single_Choice',
      values: [
        {
          type: 'Choice',
          constructor: 'Option',
          label: 'First',
          value: '(Index_Sub_Range.First 1)',
          parameters: [],
          icon: '',
        },
      ],
      label: null,
      display: {
        type: 'Display',
        constructor: 'Always',
      },
      allow_custom: true,
    },
  ]
  const vectorEditorData = [
    'list',
    {
      type: 'Widget',
      constructor: 'Vector_Editor',
      item_editor: singleChoiceData[1],
      item_default: 'Text',
      display: {
        type: 'Display',
        constructor: 'Always',
      },
    },
  ]

  const singleChoiceExpected = [
    'range',
    {
      kind: 'Single_Choice',
      values: [{ label: 'First', value: '(Index_Sub_Range.First 1)', parameters: [] }],
      label: null,
      display: DisplayMode.Always,
    },
  ]

  const vectorEditorExpected = [
    'list',
    {
      kind: 'Vector_Editor',
      item_editor: singleChoiceExpected[1],
      item_default: 'Text',
    },
  ]

  test.each([
    { input: [['self', null]], expected: [['self', null]] },
    { input: [singleChoiceData], expected: [singleChoiceExpected] },
    { input: [vectorEditorData], expected: [vectorEditorExpected] },
  ])('Testing engine configuration', ({ input, expected }) => {
    const res = widgetConfigurationSchema.safeParse(input)
    expect(res).toMatchObject({ success: true, data: expected })
  })
})
/* eslint-enable camelcase */
