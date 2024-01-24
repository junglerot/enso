import { asNodeId, GraphDb, type NodeId } from '@/stores/graph/graphDatabase'
import { assert } from '@/util/assert'
import { Ast } from '@/util/ast'
import { moduleMethodNames } from '@/util/ast/abstract'
import { nodeFromAst } from '@/util/ast/node'
import { unwrap } from '@/util/data/result'
import { tryIdentifier, type Identifier } from '@/util/qualifiedName'
import * as set from 'lib0/set'
import { IdMap } from '../../../shared/yjsModel'

// === Types ===

/** Information about code transformations needed to collapse the nodes. */
interface CollapsedInfo {
  extracted: ExtractedInfo
  refactored: RefactoredInfo
}

/** The information about the extracted function. */
interface ExtractedInfo {
  /** Nodes with these ids should be moved to the function body, in their original order. */
  ids: Set<NodeId>
  /** The output information of the function. */
  output: Output | null
  /** The list of extracted function’s argument names. */
  inputs: Identifier[]
}

/** The information about the output value of the extracted function. */
interface Output {
  /** The id of the node the expression of which should be replaced by the function call.
   * This node is also included into `ids` of the {@link ExtractedInfo} and must be moved into the extracted function.
   */
  node: NodeId
  /** The identifier of the return value of the extracted function. */
  identifier: Identifier
}

/** The information about the refactored node, the one that needs to be replaced with the function call. */
interface RefactoredInfo {
  /** The id of the refactored node. */
  id: NodeId
  /** The pattern of the refactored node. Included for convenience, collapsing does not affect it. */
  pattern: string
  /** The list of necessary arguments for a call of the collapsed function. */
  arguments: Identifier[]
}

// === prepareCollapsedInfo ===

/** Prepare the information necessary for collapsing nodes.
 * @throws errors in case of failures, but it should not happen in normal execution.
 */
export function prepareCollapsedInfo(selected: Set<NodeId>, graphDb: GraphDb): CollapsedInfo {
  if (selected.size == 0) throw new Error('Collapsing requires at least a single selected node.')
  // Leaves are the nodes that have no outgoing connection.
  const leaves = new Set([...selected])
  const inputs: Identifier[] = []
  let output: Output | null = null
  for (const [targetExprId, sourceExprIds] of graphDb.allConnections.allReverse()) {
    const target = graphDb.getExpressionNodeId(targetExprId)
    if (target == null) continue
    for (const sourceExprId of sourceExprIds) {
      const source = graphDb.getPatternExpressionNodeId(sourceExprId)
      const startsInside = source != null && selected.has(source)
      const endsInside = selected.has(target)
      const stringIdentifier = graphDb.getOutputPortIdentifier(sourceExprId)
      if (stringIdentifier == null)
        throw new Error(`Source node (${source}) has no output identifier.`)
      const identifier = unwrap(tryIdentifier(stringIdentifier))
      if (source != null) {
        leaves.delete(source)
      }
      if (!startsInside && endsInside) {
        inputs.push(identifier)
      } else if (startsInside && !endsInside) {
        if (output == null) {
          output = { node: source, identifier }
        } else if (output.identifier == identifier) {
          // Ignore duplicate usage of the same identifier.
        } else {
          throw new Error(
            `More than one output from collapsed function: ${identifier} and ${output.identifier}. Collapsing is not supported.`,
          )
        }
      }
    }
  }
  // If there is no output found so far, it means that none of our nodes is used outside
  // the extracted function. In such we will return value from arbitrarily chosen leaf.
  if (output == null) {
    const arbitraryLeaf = set.first(leaves)
    if (arbitraryLeaf == null)
      throw new Error('Cannot select the output node, no leaf nodes found.')
    const outputNode = graphDb.nodeIdToNode.get(arbitraryLeaf)
    if (outputNode == null) throw new Error(`The node with id ${arbitraryLeaf} not found.`)
    const identifier = unwrap(tryIdentifier(outputNode.pattern?.code() || ''))
    output = { node: arbitraryLeaf, identifier }
  }

  const pattern = graphDb.nodeIdToNode.get(output.node)?.pattern?.code() ?? ''

  return {
    extracted: {
      ids: selected,
      output,
      inputs,
    },
    refactored: {
      id: output.node,
      pattern,
      arguments: inputs,
    },
  }
}

/** Generate a safe method name for a collapsed function using `baseName` as a prefix. */
function findSafeMethodName(module: Ast.Module, baseName: string): string {
  const allIdentifiers = moduleMethodNames(module)
  if (!allIdentifiers.has(baseName)) {
    return baseName
  }
  let index = 1
  while (allIdentifiers.has(`${baseName}${index}`)) {
    index++
  }
  return `${baseName}${index}`
}

// === performCollapse ===

// We support working inside `Main` module of the project at the moment.
const MODULE_NAME = 'Main'
const COLLAPSED_FUNCTION_NAME = 'collapsed'

interface CollapsingResult {
  /** The ID of the node refactored to the collapsed function call. */
  refactoredNodeId: NodeId
  /** IDs of nodes inside the collapsed function, except the output node.
   * The order of these IDs is reversed comparing to the order of nodes in the source code.
   */
  collapsedNodeIds: NodeId[]
  /** ID of the output node inside the collapsed function. */
  outputNodeId?: NodeId | undefined
}

/** Perform the actual AST refactoring for collapsing nodes. */
export function performCollapse(
  info: CollapsedInfo,
  edit: Ast.MutableModule,
  topLevel: Ast.BodyBlock,
  db: GraphDb,
  currentMethodName: string,
): CollapsingResult {
  const functionAst = Ast.findModuleMethod(edit, currentMethodName)
  if (!(functionAst instanceof Ast.Function) || !(functionAst.body instanceof Ast.BodyBlock)) {
    throw new Error(`Expected a collapsable function, found ${functionAst}.`)
  }
  const functionBlock = functionAst.body
  const posToInsert = findInsertionPos(edit, topLevel, currentMethodName)
  const collapsedName = findSafeMethodName(edit, COLLAPSED_FUNCTION_NAME)
  const astIdsToExtract = new Set(
    [...info.extracted.ids].map((nodeId) => db.nodeIdToNode.get(nodeId)?.outerExprId),
  )
  const astIdToReplace = db.nodeIdToNode.get(info.refactored.id)?.outerExprId
  const collapsed = []
  const refactored = []
  const { ast: refactoredAst, nodeId: refactoredNodeId } = collapsedCallAst(
    info,
    collapsedName,
    edit,
  )
  const lines = functionBlock.statements()
  for (const line of lines) {
    const astId = line.id
    const ast = edit.take(astId)?.node
    assert(ast != null)
    if (astIdsToExtract.has(astId)) {
      collapsed.push(ast)
      if (astId === astIdToReplace) {
        refactored.push({ expression: { node: refactoredAst } })
      }
    } else {
      refactored.push({ expression: { node: ast } })
    }
  }
  const collapsedNodeIds = collapsed.map((ast) => asNodeId(nodeFromAst(ast).rootSpan.id)).reverse()
  let outputNodeId: NodeId | undefined
  const outputIdentifier = info.extracted.output?.identifier
  if (outputIdentifier != null) {
    const ident = Ast.Ident.new(edit, outputIdentifier)
    collapsed.push(ident)
    outputNodeId = asNodeId(ident.id)
  }
  // Update the definiton of the refactored function.
  const refactoredBlock = Ast.BodyBlock.new(refactored, edit)
  edit.replaceRef(functionBlock.id, refactoredBlock)

  // Insert a new function.
  const args: Ast.Owned[] = info.extracted.inputs.map((arg) => Ast.Ident.new(edit, arg))
  const collapsedFunction = Ast.Function.fromExprs(edit, collapsedName, args, collapsed, true)
  topLevel.insert(edit, posToInsert, collapsedFunction)
  return { refactoredNodeId, collapsedNodeIds, outputNodeId }
}

/** Prepare a method call expression for collapsed method. */
function collapsedCallAst(
  info: CollapsedInfo,
  collapsedName: string,
  edit: Ast.MutableModule,
): { ast: Ast.Owned; nodeId: NodeId } {
  const pattern = info.refactored.pattern
  const args = info.refactored.arguments
  const functionName = `${MODULE_NAME}.${collapsedName}`
  const expression = functionName + (args.length > 0 ? ' ' : '') + args.join(' ')
  const expressionAst = Ast.parse(expression, edit)
  const ast = Ast.Assignment.new(edit, pattern, expressionAst)
  return { ast, nodeId: asNodeId(expressionAst.id) }
}

/** Find the position before the current method to insert a collapsed one. */
function findInsertionPos(
  module: Ast.Module,
  topLevel: Ast.BodyBlock,
  currentMethodName: string,
): number {
  const currentFuncPosition = topLevel.lines().findIndex((line) => {
    const node = line.expression?.node
    const expr = node ? module.get(node.id)?.innerExpression() : null
    return expr instanceof Ast.Function && expr.name?.code() === currentMethodName
  })

  return currentFuncPosition === -1 ? 0 : currentFuncPosition
}

// === Tests ===

if (import.meta.vitest) {
  const { test, expect } = import.meta.vitest

  function setupGraphDb(code: string, graphDb: GraphDb) {
    const ast = Ast.parseTransitional(code, new IdMap())
    const expressions = Array.from(ast.statements())
    const func = expressions[0]
    assert(func instanceof Ast.Function)
    graphDb.readFunctionAst(func, () => undefined)
  }

  interface TestCase {
    description: string
    initialNodes: string[]
    selectedNodesRange: { start: number; end: number }
    expected: {
      extracted: {
        inputs: string[]
        nodes: string[]
        output: string
      }
      refactored: {
        replace: string
        with: { pattern: string; arguments: string[] }
      }
    }
  }

  const testCases: TestCase[] = [
    {
      description: 'Basic',
      initialNodes: ['a = 1', 'b = 2', 'c = A + B', 'd = a + b', 'e = c + 7'],
      selectedNodesRange: { start: 1, end: 4 },
      expected: {
        extracted: {
          inputs: ['a'],
          nodes: ['b = 2', 'c = A + B', 'd = a + b'],
          output: 'c',
        },
        refactored: {
          replace: 'c = A + B',
          with: { pattern: 'c', arguments: ['a'] },
        },
      },
    },
    {
      description: 'Refactoring a single assignment',
      initialNodes: ['a = 1', 'b = 2', 'c = A + B', 'd = a + b', 'e = c + 7'],
      selectedNodesRange: { start: 3, end: 4 },
      expected: {
        extracted: {
          inputs: ['a', 'b'],
          nodes: ['d = a + b'],
          output: 'd',
        },
        refactored: {
          replace: 'd = a + b',
          with: { pattern: 'd', arguments: ['a', 'b'] },
        },
      },
    },
    {
      description: 'No arguments and multiple result usages',
      initialNodes: ['c = 50 + d', 'x = c + c + 10'],
      selectedNodesRange: { start: 0, end: 1 },
      expected: {
        extracted: {
          inputs: [],
          nodes: ['c = 50 + d'],
          output: 'c',
        },
        refactored: {
          replace: 'c = 50 + d',
          with: { pattern: 'c', arguments: [] },
        },
      },
    },
    {
      description: 'Regression https://github.com/enso-org/ide/issues/1234',
      initialNodes: [
        'number1 = 1',
        'number2 = 2',
        'range = number1.up_to number2',
        'vector = range.to_vector',
      ],
      selectedNodesRange: { start: 2, end: 4 },
      expected: {
        extracted: {
          inputs: ['number1', 'number2'],
          nodes: ['range = number1.up_to number2', 'vector = range.to_vector'],
          output: 'vector',
        },
        refactored: {
          replace: 'vector = range.to_vector',
          with: { pattern: 'vector', arguments: ['number1', 'number2'] },
        },
      },
    },
  ]

  test.each(testCases)('Collapsing nodes, $description', (testCase) => {
    const initialCode =
      'main = \n' + testCase.initialNodes.map((code) => ' '.repeat(4) + code).join('\n')
    const graphDb = GraphDb.Mock()
    setupGraphDb(initialCode, graphDb)
    const expectedExtracted = testCase.expected.extracted
    const expectedRefactored = testCase.expected.refactored
    const nodes = Array.from(graphDb.nodeIdToNode.entries())
    expect(nodes.length).toEqual(testCase.initialNodes.length)
    const nodeCodeToId = new Map<string, NodeId>()
    const nodePatternToId = new Map<string, NodeId>()
    for (const code of testCase.initialNodes) {
      const [pattern, expr] = code.split(/\s*=\s*/)
      const [id, _] = nodes.find(([_id, node]) => node.rootSpan.code() == expr)!
      nodeCodeToId.set(code, id)
      if (pattern != null) nodePatternToId.set(pattern, id)
    }
    assert(nodeCodeToId.size == nodePatternToId.size)
    const range = testCase.selectedNodesRange
    const selectedNodesCode = testCase.initialNodes.slice(range.start, range.end)
    const selectedNodes = new Set(selectedNodesCode.map((code) => nodeCodeToId.get(code)!))

    const { extracted, refactored } = prepareCollapsedInfo(selectedNodes, graphDb)
    const expectedInputs = expectedExtracted.inputs.map((s) => unwrap(tryIdentifier(s)))
    const expectedOutput = unwrap(tryIdentifier(expectedExtracted.output))
    const expectedIds = expectedExtracted.nodes.map((code) => nodeCodeToId.get(code)!)
    const expectedRefactoredId = nodeCodeToId.get(expectedRefactored.replace)!

    expect(extracted.inputs).toEqual(expectedInputs)
    expect(extracted.output).toEqual({
      node: nodePatternToId.get(expectedOutput),
      identifier: expectedOutput,
    })
    expect(extracted.ids).toEqual(new Set(expectedIds))
    expect(refactored.id).toEqual(expectedRefactoredId)
    expect(refactored.pattern).toEqual(expectedRefactored.with.pattern)
    expect(refactored.arguments).toEqual(expectedRefactored.with.arguments)
  })
}
