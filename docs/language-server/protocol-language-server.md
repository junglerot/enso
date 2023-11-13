---
layout: developer-doc
title: Enso Protocol Language Server Message Specification
category: language-server
tags: [language-server, protocol, specification]
order: 4
---

# Enso Protocol Language Server Message Specification

This document contains the specification of the Enso protocol messages that
pertain to the language server component. Please familiarise yourself with the
[common](./protocol-common.md) features of the protocol before reading this
document.

For information on the design and architecture of the protocol, as well as its
transport formats, please look [here](./protocol-architecture).

<!-- MarkdownTOC levels="2,3" autolink="true" -->

- [Types](#types)
  - [`ExpressionId`](#expressionid)
  - [`ContextId`](#contextid)
  - [`StackItem`](#stackitem)
  - [`MethodCall`](#methodcall)
  - [`MethodPointer`](#methodpointer)
  - [`ProfilingInfo`](#profilinginfo)
  - [`ExpressionUpdate`](#expressionupdate)
  - [`ExpressionUpdatePayload`](#expressionupdatepayload)
  - [`VisualizationConfiguration`](#visualizationconfiguration)
  - [`VisualizationExpression`](#visualizationexpression)
  - [`SuggestionEntryArgument`](#suggestionentryargument)
  - [`SuggestionEntry`](#suggestionentry)
  - [`SuggestionEntryType`](#suggestionentrytype)
  - [`SuggestionId`](#suggestionid)
  - [`DocSection`](#docsection)
  - [`SuggestionsDatabaseEntry`](#suggestionsdatabaseentry)
  - [`FieldAction`](#fieldaction)
  - [`FieldUpdate`](#fieldupdate)
  - [`SuggestionArgumentUpdate`](#suggestionargumentupdate)
  - [`SuggestionsDatabaseUpdate`](#suggestionsdatabaseupdate)
  - [`SuggestionsOrderDatabaseUpdate`](#suggestionsorderdatabaseupdate)
  - [`Export`](#export)
  - [`File`](#file)
  - [`DirectoryTree`](#directorytree)
  - [`FileAttributes`](#fileattributes)
  - [`UTCDateTime`](#utcdatetime)
  - [`FileEventKind`](#fileeventkind)
  - [`Position`](#position)
  - [`Range`](#range)
  - [`TextEdit`](#textedit)
  - [`DiagnosticType`](#diagnostictype)
  - [`StackTraceElement`](#stacktraceelement)
  - [`ExecutionResult`](#executionresult)
  - [`Diagnostic`](#diagnostic)
  - [`SHA3-224`](#sha3-224)
  - [`FileEdit`](#fileedit)
  - [`FileContents`](#filecontents)
  - [`FileSystemObject`](#filesystemobject)
  - [`WorkspaceEdit`](#workspaceedit)
  - [`EnsoDigest`](#ensodigest)
  - [`FileSegment`](#filesegment)
  - [`ContentRoot`](#contentroot)
  - [`LibraryEntry`](#libraryentry)
  - [`LibraryVersion`](#libraryversion)
  - [`Contact`](#contact)
  - [`EditionReference`](#editionreference)
  - [`LibraryComponentGroups`](#librarycomponentgroups)
  - [`LibraryComponentGroup`](#librarycomponentgroup)
  - [`LibraryComponent`](#librarycomponent)
- [Connection Management](#connection-management)
  - [`session/initProtocolConnection`](#sessioninitprotocolconnection)
  - [`session/initBinaryConnection`](#sessioninitbinaryconnection)
- [Capability Management](#capability-management)
  - [`capability/acquire`](#capabilityacquire)
  - [`capability/release`](#capabilityrelease)
  - [`capability/granted`](#capabilitygranted)
  - [`capability/forceReleased`](#capabilityforcereleased)
- [Capabilities](#capabilities)
  - [`text/canEdit`](#textcanedit)
  - [`file/receivesTreeUpdates`](#filereceivestreeupdates)
  - [`executionContext/canModify`](#executioncontextcanmodify)
  - [`executionContext/receivesUpdates`](#executioncontextreceivesupdates)
  - [`search/receivesSuggestionsDatabaseUpdates`](#searchreceivessuggestionsdatabaseupdates)
- [File Management Operations](#file-management-operations)
  - [`file/write`](#filewrite)
  - [`file/read`](#fileread)
  - [`file/writeBinary`](#filewritebinary)
  - [`file/readBinary`](#filereadbinary)
  - [`file/writeBytes`](#filewritebytes)
  - [`file/readBytes`](#filereadbytes)
  - [`file/create`](#filecreate)
  - [`file/delete`](#filedelete)
  - [`file/copy`](#filecopy)
  - [`file/move`](#filemove)
  - [`file/exists`](#fileexists)
  - [`file/tree`](#filetree)
  - [`file/list`](#filelist)
  - [`file/info`](#fileinfo)
  - [`file/checksum`](#filechecksum)
  - [`file/checksumBytes`](#filechecksumbytes)
  - [`file/event`](#fileevent)
  - [`file/rootAdded`](#filerootadded)
  - [`file/rootRemoved`](#filerootremoved)
- [Version Control System](#vcs-operations)
  - [`vcs/init`](#vcsinit)
  - [`vcs/list`](#vcslist)
  - [`vcs/restore`](#vcsrestore)
  - [`vcs/save`](#vcssave)
  - [`vcs/status`](#vcsstatus)
- [Text Editing Operations](#text-editing-operations)
  - [`text/openFile`](#textopenfile)
  - [`text/openBuffer`](#textopenbuffer)
  - [`text/closeFile`](#textclosefile)
  - [`text/save`](#textsave)
  - [`text/applyEdit`](#textapplyedit)
  - [`text/applyExpressionValue`](#textapplyexpressionvalue)
  - [`text/didChange`](#textdidchange)
  - [`text/autoSave`](#textautosave)
  - [`text/fileModifiedOnDisk`](#textfilemodifiedondisk)
- [Workspace Operations](#workspace-operations)
  - [`workspace/projectInfo`](#workspaceprojectinfo)
- [Monitoring](#monitoring)
  - [`heartbeat/ping`](#heartbeatping)
  - [`heartbeat/init`](#heartbeatinit)
- [Refactoring](#refactoring)
  - [`refactoring/renameProject`](#refactoringrenameproject)
  - [`refactoring/renameSymbol`](#refactoringrenamesymbol)
  - [`refactoring/projectRenamed`](#refactoringprojectrenamed)
- [Execution Management Operations](#execution-management-operations)
  - [Execution Management Example](#execution-management-example)
  - [Create Execution Context](#create-execution-context)
  - [Push Item](#push-item)
  - [Pop Item](#pop-item)
  - [`executionContext/create`](#executioncontextcreate)
  - [`executionContext/destroy`](#executioncontextdestroy)
  - [`executionContext/fork`](#executioncontextfork)
  - [`executionContext/push`](#executioncontextpush)
  - [`executionContext/pop`](#executioncontextpop)
  - [`executionContext/recompute`](#executioncontextrecompute)
  - [`executionContext/interrupt`](#executioncontextinterrupt)
  - [`executionContext/setExecutionEnvironment`](#executioncontextsetexecutionenvironment)
  - [`executionContext/getComponentGroups`](#executioncontextgetcomponentgroups)
  - [`executionContext/expressionUpdates`](#executioncontextexpressionupdates)
  - [`executionContext/executionFailed`](#executioncontextexecutionfailed)
  - [`executionContext/executionComplete`](#executioncontextexecutioncomplete)
  - [`executionContext/executionStatus`](#executioncontextexecutionstatus)
  - [`executionContext/executeExpression`](#executioncontextexecuteexpression)
  - [`executionContext/attachVisualization`](#executioncontextattachvisualization)
  - [`executionContext/detachVisualization`](#executioncontextdetachvisualization)
  - [`executionContext/modifyVisualization`](#executioncontextmodifyvisualization)
  - [`executionContext/visualizationUpdate`](#executioncontextvisualizationupdate)
  - [`executionContext/visualizationEvaluationFailed`](#executioncontextvisualizationevaluationfailed)
- [Search Operations](#search-operations)
  - [Suggestions Database Example](#suggestions-database-example)
  - [`search/getSuggestionsDatabase`](#searchgetsuggestionsdatabase)
  - [`search/invalidateSuggestionsDatabase`](#searchinvalidatesuggestionsdatabase)
  - [`search/getSuggestionsDatabaseVersion`](#searchgetsuggestionsdatabaseversion)
  - [`search/suggestionsDatabaseUpdate`](#searchsuggestionsdatabaseupdate)
  - [`search/suggestionsOrderDatabaseUpdate`](#searchsuggestionsorderdatabaseupdate)
  - [`search/completion`](#searchcompletion)
- [Input/Output Operations](#inputoutput-operations)
  - [`io/redirectStandardOutput`](#ioredirectstandardoutput)
  - [`io/suppressStandardOutput`](#iosuppressstandardoutput)
  - [`io/standardOutputAppended`](#iostandardoutputappended)
  - [`io/redirectStandardError`](#ioredirectstandarderror)
  - [`io/suppressStandardError`](#iosuppressstandarderror)
  - [`io/standardErrorAppended`](#iostandarderrorappended)
  - [`io/feedStandardInput`](#iofeedstandardinput)
  - [`io/waitingForStandardInput`](#iowaitingforstandardinput)
- [Library-Related Operations](#library-related-operations)
  - [`editions/listAvailable`](#editionslistavailable)
  - [`editions/resolve`](#editionsresolve)
  - [`editions/getProjectSettings`](#editionsgetprojectsettings)
  - [`editions/setProjectParentEdition`](#editionssetprojectparentedition)
  - [`editions/setProjectLocalLibrariesPreference`](#editionssetprojectlocallibrariespreference)
  - [`editions/listDefinedLibraries`](#editionslistdefinedlibraries)
  - [`editions/listDefinedComponents`](#editionslistdefinedcomponents)
  - [`library/listLocal`](#librarylistlocal)
  - [`library/create`](#librarycreate)
  - [`library/getMetadata`](#librarygetmetadata)
  - [`library/setMetadata`](#librarysetmetadata)
  - [`library/getPackage`](#librarygetpackage)
  - [`library/publish`](#librarypublish)
  - [`library/preinstall`](#librarypreinstall)
- [Runtime Operations](#runtime-operations)
  - [`runtime/getComponentGroups`](#runtimegetcomponentgroups)
- [Errors](#errors-75)
  - [`Error`](#error)
  - [`AccessDeniedError`](#accessdeniederror)
  - [`FileSystemError`](#filesystemerror)
  - [`ContentRootNotFoundError`](#contentrootnotfounderror)
  - [`FileNotFound`](#filenotfound)
  - [`FileExists`](#fileexists)
  - [`OperationTimeoutError`](#operationtimeouterror)
  - [`NotDirectory`](#notdirectory)
  - [`NotFile`](#notfile)
  - [`CannotOverwrite`](#cannotoverwrite)
  - [`ReadOutOfBounds`](#readoutofbounds)
  - [`CannotDecode`](#cannotdecode)
  - [`StackItemNotFoundError`](#stackitemnotfounderror)
  - [`ContextNotFoundError`](#contextnotfounderror)
  - [`EmptyStackError`](#emptystackerror)
  - [`InvalidStackItemError`](#invalidstackitemerror)
  - [`ModuleNotFoundError`](#modulenotfounderror)
  - [`VisualizationNotFoundError`](#visualizationnotfounderror)
  - [`VisualizationExpressionError`](#visualizationexpressionerror)
  - [`FileNotOpenedError`](#filenotopenederror)
  - [`TextEditValidationError`](#texteditvalidationerror)
  - [`InvalidVersionError`](#invalidversionerror)
  - [`WriteDeniedError`](#writedeniederror)
  - [`CapabilityNotAcquired`](#capabilitynotacquired)
  - [`SessionNotInitialisedError`](#sessionnotinitialisederror)
  - [`SessionAlreadyInitialisedError`](#sessionalreadyinitialisederror)
  - [`ResourcesInitializationError`](#resourcesinitializationerror)
  - [`SuggestionsDatabaseError`](#suggestionsdatabaseerror)
  - [`ProjectNotFoundError`](#projectnotfounderror)
  - [`ModuleNameNotResolvedError`](#modulenamenotresolvederror)
  - [`SuggestionNotFoundError`](#suggestionnotfounderror)
  - [`EditionNotFoundError`](#editionnotfounderror)
  - [`LibraryAlreadyExists`](#libraryalreadyexists)
  - [`LibraryRepositoryAuthenticationError`](#libraryrepositoryauthenticationerror)
  - [`LibraryPublishError`](#librarypublisherror)
  - [`LibraryUploadError`](#libraryuploaderror)
  - [`LibraryDownloadError`](#librarydownloaderror)
  - [`LocalLibraryNotFound`](#locallibrarynotfound)
  - [`LibraryNotResolved`](#librarynotresolved)
  - [`InvalidLibraryName`](#invalidlibraryname)
  - [`DependencyDiscoveryError`](#dependencydiscoveryerror)
  - [`InvalidSemverVersion`](#invalidsemverversion)
  - [`ExpressionNotFoundError`](#expressionnotfounderror)
  - [`FailedToApplyEdits`](#failedtoapplyedits)
  - [`RefactoringNotSupported`](#refactoringnotsupported)

<!-- /MarkdownTOC -->

## Types

There are a number of types that are used only within the language server's
protocol messages. These are specified here.

### `ExpressionId`

An identifier used for Enso expressions.

```typescript
type ExpressionId = UUID;
```

### `ContextId`

An identifier used for execution contexts.

```typescript
type ContextId = UUID;
```

### `StackItem`

A representation of an executable position in code, used by the execution APIs.

`ExplicitCall` is a call performed at the top of the stack, to initialize the
context with first execution. The `thisArgumentsPosition` field can be omitted,
in which case the context will try to infer the argument on a best-effort basis.
E.g. for a module-level method, or a method defined on a parameter-less type,
`self` will be substituted for the unambiguous singleton instance.

`LocalCall` is a call corresponding to "entering a function call".

```typescript
type StackItem = ExplicitCall | LocalCall;

interface ExplicitCall {
  methodPointer: MethodPointer;
  thisArgumentExpression?: string;
  positionalArgumentsExpressions: string[];
}

interface LocalCall {
  expressionId: ExpressionId;
}
```

### `MethodCall`

A representation of a method call.

```typescript
interface MethodCall {
  /** The method pointer of a call. */
  methodPointer: MethodPointer;

  /** Indexes of arguments that have not been applied to this method. */
  notAppliedArguments: number[];
}
```

### `MethodPointer`

Points to a method definition.

```typescript
interface MethodPointer {
  /** The fully qualified module name. */
  module: string;

  /** The type on which the method is defined. */
  definedOnType: string;

  /** The method name. */
  name: string;
}
```

### `ProfilingInfo`

Profiling information on an executed expression. It is implemented as a union as
additional types of information will be added in the future.

```typescript
type ProfilingInfo = ExecutionTime;
```

Where:

```typescript
interface ExecutionTime {
  /** The time elapsed during the expression's evaluation, in nanoseconds. */
  nanoTime: number;
}
```

### `ExecutionEnvironment`

The execution environment of Enso runtime.

```typescript
type ExecutionEnvironment = Design | Live;
```

### `ExpressionUpdate`

An update about the computed expression.

```typescript
interface ExpressionUpdate {
  /** The id of updated expression. */
  expressionId: ExpressionId;
  /** The updated type of the expression. */
  type?: string;
  /** The updated method call info. */
  methodCall?: MethodCall;
  /** Profiling information about the expression. */
  profilingInfo: ProfilingInfo[];
  /** Whether the expression's value came from the cache. */
  fromCache: boolean;
  /** An extra information about the computed value. */
  payload: ExpressionUpdatePayload;
}
```

### `ExpressionUpdatePayload`

An information about the computed value.

```typescript
type ExpressionUpdatePayload = Value | DatafalowError | Panic | Pending;

/** Indicates that the expression was computed to a value. */
interface Value {
  /** Information about attached warnings. */
  warnings?: Warnings;
  /** The schema of returned function value. */
  functionSchema?: FunctionSchema;
}

/** Indicates that the expression was computed to an error. */
interface DataflowError {
  /** The list of expressions leading to the root error. */
  trace: ExpressionId[];
}

/** Indicates that the expression failed with the runtime exception. */
interface Panic {
  /** The error message. */
  message: string;
  /** The stack trace. */
  trace: ExpressionId[];
}

/** Indicates the expression is currently being computed. Optionally it
 * provides a description and fraction of completion (`0.0-1.0`). */
interface Pending {
  /** Optional message describing current operation. */
  message?: string;
  /** Optional amount of already done work as a number between `0.0` to `1.0`.
   */
  progress?: number;
}

/** Information about warnings associated with the value. */
interface Warnings {
  /** The number of attached warnings. */
  count: number;
  /** If the value has a single warning attached, this field contains textual
   * representation of the attached warning. In general, warning values should
   * be obtained by attaching an appropriate visualization to a value. */
  value?: string;
}

/** Contains a method pointer with information on the partially applied argument
 * positions. */
interface FunctionSchema {
  /** The method pointer of this function. */
  methodPointer: MethodPointer;
  /** Indexes of arguments that have not been applied to this function. */
  notAppliedArguments: number[];
}
```

### `VisualizationConfiguration`

A configuration object for properties of the visualization.

```typescript
interface VisualizationConfiguration {
  /** An execution context of the visualization. */
  executionContextId: UUID;
  /** The qualified name of the module to be used to evaluate the arguments for
   * the visualization expression. */
  visualizationModule: string;
  /** An expression that creates a visualization. */
  expression: string | MethodPointer;
  /** A list of arguments to pass to the visualization expression. */
  positionalArgumentsExpressions?: string[];
}
```

### `SuggestionEntryArgument`

The argument of a [`SuggestionEntry`](#suggestionentry).

#### Format

```typescript
// The argument of a constructor, method or function suggestion.
interface SuggestionEntryArgument {
  /** The argument name. */
  name: string;
  /** The argument type. String 'Any' is used to specify generic types. */
  type: string;
  /** Indicates whether the argument is lazy. */
  isSuspended: boolean;
  /** Indicates whether the argument has default value. */
  hasDefault: boolean;
  /** Optional default value. */
  defaultValue?: string;
  /** Optional list of possible values that this argument takes. */
  tagValues?: string[];
}
```

### `SuggestionEntry`

The language construct that can be returned as a suggestion.

#### Format

```typescript
// The definition scope
interface SuggestionEntryScope {
  // The start position of the definition scope
  start: Position;
  // The end position of the definition scope
  end: Position;
}

// A type of suggestion entries.
type SuggestionEntry =
  // A module
  | Module
  // A type
  | Type
  // A type constructor
  | Constructor
  // A method defined on a type
  | Method
  // A function
  | Function
  // A local value
  | Local;

interface Module {
  /** The fully qualified module name. */
  module: string;

  /** The documentation string. */
  documentation?: string;

  /** The fully qualified module name re-exporting this module. */
  reexport?: string;
}

interface Type {
  /** The external id. */
  externalId?: UUID;

  /** The type name. */
  name: string;

  /** The qualified module name where the type is defined. */
  module: string;

  /** The list of type parameters. */
  params: SuggestionEntryArgument[];

  /** Qualified name of the parent type. */
  parentType?: string;

  /** The fully qualified module name re-exporting this type. */
  reexport?: string;

  /** The documentation string. */
  documentation?: string;
}

interface Constructor {
  /** The external id. */
  externalId?: UUID;

  /** The constructor name. */
  name: string;

  /** The qualified module name where this constructor is defined. */
  module: string;

  /** The list of arguments. */
  arguments: SuggestionEntryArgument[];

  /** The type of the constructor. */
  returnType: string;

  /** The fully qualified module name re-exporting this constructor. */
  reexport?: string;

  /** The documentation string. */
  documentation?: string;

  /** The list of annotations. */
  annotations: string[];
}

interface Method {
  /** The external id. */
  externalId?: UUID;

  /** The method name. */
  name: string;

  /** The module name where this method is defined. */
  module: string;

  /** The list of arguments. */
  arguments: SuggestionEntryArgument[];

  /** The method self type. */
  selfType: string;

  /** The return type of this method. */
  returnType: string;

  /** The flag indicating whether this method is static or instance. */
  isStatic: boolean;

  /** The fully qualified module name re-exporting this method. */
  reexport?: string;

  /** The documentation string. */
  documentation?: string;

  /** The list of annotations. */
  annotations: string[];
}

interface Function {
  /** The external id. */
  externalId?: UUID;

  /** The function name. */
  name: string;

  /** The module name where this function is defined. */
  module: string;

  /** The list of arguments. */
  arguments: SuggestionEntryArgument[];

  /** The function return type. */
  returnType: string;

  /** The scope where the function is defined. */
  scope: SuggestionEntryScope;

  /** The documentation string. */
  documentation?: string;
}

interface Local {
  /** The external id. */
  externalId?: UUID;

  /** The name of a value. */
  name: string;

  /** The module where this value is defined. */
  module: string;

  /** The type of a value. */
  returnType: string;

  /** The scope where the value is defined. */
  scope: SuggestionEntryScope;

  /** The documentation string. */
  documentation?: string;
}
```

### `SuggestionEntryType`

The suggestion entry type that is used as a filter in search requests.

#### Format

```typescript
// The kind of a suggestion.
type SuggestionEntryType =
  | Module
  | Type
  | Constructor
  | Method
  | Function
  | Local;
```

### `SuggestionId`

The suggestion entry id of the suggestions database.

#### Format

```typescript
type SuggestionId = number;
```

### `SuggestionsDatabaseEntry`

The entry in the suggestions database.

#### Format

```typescript
interface SuggestionsDatabaseEntry {
  /** The suggestion entry id. */
  id: SuggestionId;
  /** The suggestion entry. */
  suggestion: SuggestionEntry;
}
```

### `FieldAction`

The modifying action on a record field.

#### Format

```typescript
type FieldAction = "Remove" | "Set";
```

### `FieldUpdate`

An object representing a modification of a field in a record.

#### Format

```typescript
interface FieldUpdate<T> {
  /** The modifying action. */
  tag: FieldAction;
  /** The updated value. */
  value?: T;
}
```

### `SuggestionArgumentUpdate`

An operation applied to the suggestion argument.

#### Format

```typescript
type SuggestionArgumentUpdate =
  | SuggestionArgumentAdd
  | SuggestionArgumentRemove
  | SuggestionArgumentModify;

interface SuggestionArgumentAdd {
  /** The position of the argument. */
  index: int;
  /** The argument to add. */
  argument: SuggestionEntryArgument;
}

interface SuggestionArgumentRemove {
  /** The position of the argument. */
  index: int;
}

interface SuggestionArgumentModify {
  /** The position of the argument. */
  index: int;
  /** The new name. */
  name?: FieldUpdate<string>;
  /** The new argument type. */
  reprType?: FieldUpdate<string>;
  /** The new `isSuspended` flag. */
  isSuspended?: FieldUpdate<boolean>;
  /** The new `hasDefault` flag. */
  hasDefault?: FieldUpdate<boolean>;
  /** The new default value. */
  defaultValue?: FieldUpdate<string>;
}
```

### `SuggestionsDatabaseUpdate`

The update of the suggestions database.

#### Format

```typescript
/** The kind of the suggestions database update. */
type SuggestionsDatabaseUpdate =
  | SuggestionsDatabaseUpdateAdd
  | SuggestionsDatabaseUpdateRemove
  | SuggestionsDatabaseUpdateModify;

interface SuggestionsDatabaseUpdateAdd {
  /** Suggestion entry id. */
  id: SuggestionId;
  /** Suggestion entry. */
  suggestion: SuggestionEntry;
}

interface SuggestionsDatabaseUpdateRemove {
  /** Suggestion entry id. */
  id: SuggestionId;
}

interface SuggestionsDatabaseUpdateModify {
  /** Suggestion entry id. */
  id: SuggestionId;
  /** The new external id. */
  externalId?: FieldUpdate<UUID>;
  /** The list of argument updates. */
  arguments?: SuggestionArgumentUpdate[];
  /** The new module name. */
  module?: FieldUpdate<string>;
  /** The new self type. */
  selfType?: FieldUpdate<string>;
  /** The new return type. */
  returnType?: FieldUpdate<string>;
  /** The new documentation string. */
  documentation?: FieldUpdate<string>;
  /** The new scope. */
  scope?: FieldUpdate<SuggestionEntryScope>;
  /** The new reexport field. */
  reexport?: FieldUpdate<string>;
}
```

### `SuggestionsOrderDatabaseUpdate`

The update of the suggestions order database.

#### Format

```typescript
/** The kind of the suggestions order database update. */
type SuggestionsOrderDatabaseUpdate =
  | SuggestionsOrderDatabaseUpdateAdd
  | SuggestionsOrderDatabaseUpdateRemove
  | SuggestionsOrderDatabaseUpdateModify;

interface SuggestionsOrderDatabaseUpdateAdd {
  entry: SuggestionOrderDatabaseEntry;
}

interface SuggestionsOrderDatabaseUpdateRemove {
  /** The unique identifier of a suggestion. */
  suggestionId: SuggestionId;
}

interface SuggestionsOrderDatabaseUpdateModify {
  /** The unique identifier of a suggestion. */
  suggestionId: SuggestionId;
  /** The previous suggestion id to update. */
  prevId?: FieldUpdate<SuggestionId>;
  /** The next suggestion id to update. */
  nextId?: FieldUpdate<SuggestionId>;
}
```

### `Export`

The info about module re-export.

#### Format

```typescript
type Export = ExportQualified | ExportUnqualified;

interface ExportQualified {
  /** The module that re-exports the given module. */
  module: string;
  /** The new name of the given module if it was renamed in the export clause.
   *
   * e.g. `X` in `export A.B as X`. */
  alias?: string;
}

interface ExportUnqualified {
  /** The module name that re-exports the given module. */
  module: string;
}
```

### `File`

A representation of a file on disk.

#### Format

```typescript
interface File {
  /** Includes the file extension. */
  name: string;
  type: string;
}
```

### `DirectoryTree`

A directory tree is a recursive type used to represent tree structures of files
and directories. It contains files and symlinks in the `files` section and
directories in the `directories` section. When the tree was requested with the
parameter limiting the maximum depth, the bottom of the `DirectoryTree` will
contain `Directory` node in the `files` section indicating that there is a
directory, but the contents are unknown because we've reached the maximum depth.

#### Format

```typescript
interface DirectoryTree {
  path: Path;
  name: string;
  files: FileSystemObject[];
  directories: DirectoryTree[];
}
```

### `FileAttributes`

A description of the attributes of a file required by the IDE. These attributes
may be expanded in future.

#### Format

```typescript
/** A representation of the attributes of a file.
 *
 * @param creationTime creation time
 * @param lastAccessTime last access time
 * @param lastModifiedTime last modified time
 * @param kind type of [[FileSystemObject]], can be: `Directory`, `File`, `Other`
 * @param byteSize size in bytes */
interface FileAttributes {
  creationTime: UTCDateTime;
  lastAccessTime: UTCDateTime;
  lastModifiedTime: UTCDateTime;
  kind: FileSystemObject;
  byteSize: number;
}
```

### `UTCDateTime`

Time in UTC time zone represented as ISO-8601 string

#### Format

```typescript
type UTCDateTime = string;
```

### `FileEventKind`

The kind of event being described for a watched file.

#### Format

```typescript
type FileEventKind = "Added" | "Removed" | "Modified";
```

### `Position`

A representation of a position in a text file.

#### Format

```typescript
interface Position {
  /** Line position in a document (zero-based). */
  line: number;
  /** Character offset on a line in a document (zero-based). Assuming that the
   * line is represented as a string, the `character` value represents the gap
   * between the `character` and `character + 1`.
   *
   * If the character value is greater than the line length it defaults back to
   * the line length. */
  character: number;
}
```

```csharp
namespace org.enso.languageserver.protocol.binary;

struct Position {
  // Line position in a document (zero-based)
  line: uint64;
  // Character offset on a line in a document (zero-based)
  character: uint64;
}
```

### `Range`

A representation of a range of text in a text file.

For example, given the function.

```rust
0|inc x =
1|    x + 1
  ^^^^^^^^^
  012345678
```

The range of `inc` is

```typescript
{
    start: { line: 0, character: 0},
    end: { line: 0, character: 3}
}
```

The range of `1` is

```typescript
{
    start: { line: 1, character: 8},
    end: { line: 1, character: 9}
}
```

#### Format

```typescript
interface Range {
  /** The range's start position (inclusive). */
  start: Position;
  /** The range's end position (exclusive). */
  end: Position;
}
```

### `TextEdit`

A representation of a change to a text file at a given position.

#### Format

```typescript
interface TextEdit {
  /** The range of text in a text file. */
  range: Range;
  /** The change to a text file. */
  text: string;
}
```

### `DiagnosticType`

The type of diagnostic message.

#### Format

```typescript
type DiagnosticType = "Error" | "Warning";
```

### `StackTraceElement`

The frame of the stack trace. If the error refer to a builtin node, the `path`
and `location` fields will be empty.

#### Format

```typescript
interface StackTraceElement {
  /** The function name containing the stack trace element. */
  functionName: string;
  /** The location of the file. */
  path?: Path;
  /** The location of the element in the file. */
  location?: Range;
}
```

### `ExecutionResult`

An execution result object is produced as a result of an execution attempt.
Compared to `Diagnostic` object it can also represent a critical failure
information.

### `Diagnostic`

A diagnostic object is produced as a result of an execution attempt, like
pushing the method pointer to a call stack, or editing the file. It can
represent a compiler warning, a compilation error, or a runtime error. The
message has optional `path`, `location` and `stack` fields containing
information about the location in the source code.

In case of the runtime errors, the `path` and `location` fields may be empty if
the error happens in a builtin node. Then, to locate the error in the code, you
can use the `stack` field with a stack trace to find the first element with
non-empty location (as the head of the stack will point to the builtin element).

#### Format

```typescript
interface Diagnostic {
  /** The type of diagnostic message. */
  kind: DiagnosticType;
  /** The diagnostic message. */
  message: string;
  /** The location of the file containing the diagnostic. */
  path?: Path;
  /** The location of the diagnostic object in the file. */
  location?: Range;
  /** The id of the related expression. */
  expressionId?: ExpressionId;
  /** The stack trace. */
  stack: StackTraceElement[];
}
```

### `SHA3-224`

The `SHA3-224` message digest encoded as a base16 string. For the equivalent
structure on the binary connection please see [`EnsoDigest`](#ensodigest)

#### Format

```typescript
type SHA3_224 = string;
```

### `FileEdit`

A representation of a batch of edits to a file, versioned.

`SHA3_224` represents hash of the file contents. `oldVersion` is the version
you're applying your update on, `newVersion` is what you compute as the hash
after applying the changes. In other words,

```python
hash(origFile) == oldVersion
hash(applyEdits(origFile, edits)) == newVersion
```

it's a sanity check to make sure that the diffs are applied consistently.

Consecutive text edits are applied sequentially, every one acting on the result
of applying previous ones on the original buffer contents. In pseudocode:

```haskell
applyEdits buffer [] = buffer
applyEdits buffer (first : rest) = applyEdits (applyTextEdit buffer first) rest
```

#### Format

```typescript
interface FileEdit {
  path: Path;
  edits: TextEdit[];
  oldVersion: SHA3_224;
  newVersion: SHA3_224;
}
```

### `FileContents`

A representation of the contents of a file.

#### Format

```typescript
interface FileContents<T> {
  contents: T;
}

class TextFileContents extends FileContents<string> {}
```

### `FileSystemObject`

A representation of what kind of type a filesystem object can be.

#### Format

```typescript
type FileSystemObject = Directory | SymlinkLoop | File | Other;

/**
 * Represents a directory.
 *
 * @param name a name of the directory
 * @param path a path to the directory
 */
interface Directory {
  name: string;
  path: Path;
}

/**
 * Represents a symbolic link that creates a loop.
 *
 * @param name a name of the symlink
 * @param path a path to the symlink
 * @param target a target of the symlink. Since it is a loop,
 * target is a subpath of the symlink
 */
interface SymlinkLoop {
  name: string;
  path: Path;
  target: Path;
}

/**
 * Represents a file.
 *
 * @param name a name of the file
 * @param path a path to the file
 */
interface File {
  name: string;
  path: Path;
}

/**
 * Represents unrecognized object.
 * Example is a broken symbolic link.
 */
interface Other {
  name: string;
  path: Path;
}
```

### `WorkspaceEdit`

This is a message to be specified once we better understand the intricacies of
undo/redo.

> The actionables for this section are:
>
> - Work out the design of this message.
> - Specify this message.

### `EnsoDigest`

A counterpart to [SHA3-224](#sha3-224) for the binary connection, this is a
standard message digest encoded using FlatBuffers.

```csharp
namespace org.enso.languageserver.protocol.binary;

table EnsoDigest {
  bytes : [ubyte] (required);
}
```

Notes:

- It is an error for the length of the vector `bytes` to not be equal to 28 (224
  / 8). This is the length of the chosen digest in bytes.

### `FileSegment`

A representation of a segment of a file for use in the binary protocol.

```csharp
namespace org.enso.languageserver.protocol.binary;

table FileSegment {
  // The file to access.
  path : Path (required);
  // The byte offset in the file to read from.
  byteOffset : ulong;
  // The number of bytes to read.
  length : ulong;
}
```

The `byteOffset` property is zero-indexed, so the last byte in the file is at
index `file.length - 1`.

### `ContentRoot`

A representation of a content root for use in the IDE. A content root represents
a location on a real file-system that has been virtualised for use in the Enso
VFS.

```typescript
type ContentRoot = Project | FileSystemRoot | Home | Library | Custom;
```

```typescript
/** This content root points to the project home. */
interface Project {
  /** A unique identifier for the content root. */
  id: UUID;
}

/** This content root points to the system root (`/`) on unix systems, or to a
 * drive root on Windows. In Windows' case, there may be multiple `Root` entries
 * corresponding to the various drives. */
interface FileSystemRoot {
  /** A unique identifier for the content root. */
  id: UUID;
  /** The absolute filesystem path of the content root. */
  path: string;
}

/** The user's home directory. */
interface Home {
  /** A unique identifier for the content root. */
  id: UUID;
}

/** An Enso library location. */
interface Library {
  /** A unique identifier for the content root. */
  id: UUID;
  /** The namespace of the library. */
  namespace: string;
  /** The name of the library. */
  name: string;
  /** The version of the library.
   * It is either a semver version or the string "local". */
  version: string;
}

/** A content root that has been added by the IDE (unused for now). */
interface Custom {
  /** A unique identifier for the content root. */
  id: UUID;
}
```

### `LibraryEntry`

Represents a library available in a resolved edition.

```typescript
interface LibraryEntry {
  namespace: string;
  name: string;
  version: LibraryVersion;
  isCached: boolean;
}
```

### `LibraryVersion`

Represents a library version, as returned in `LibraryEntry`.

```typescript
type LibraryVersion = LocalLibraryVersion | PublishedLibraryVersion;

/** A library version that references a version of the library published in some
 * repository. */
interface PublishedLibraryVersion {
  /** A semver-compliant version of the library. */
  version: string;
  /** A URL to the repository from which this library will be downloaded. */
  repositoryUrl: string;
}

/** A library version that references a locally editable version of the library.
 */
interface LocalLibraryVersion {}
```

### `Contact`

Represents contact information of authors or maintainers.

Both fields are optional, but for the contact to be valid, at least one of them
must be defined.

```typescript
interface Contact {
  name?: string;
  email?: string;
}
```

### `EditionReference`

A reference to a specific edition.

Currently, it can either reference an edition by its name, or reference the
edition associated with the currently open project.

```typescript
type EditionReference = NamedEdition | CurrentProjectEdition;

/** The edition associated with the current project, with all of its overrides.
 */
interface CurrentProjectEdition {}

/** An edition stored under a given name. */
interface NamedEdition {
  editionName: string;
}
```

### `LibraryComponentGroups`

The description of component groups provided by the package. Object fields can
be omitted if the corresponding list is empty.

```typescript
interface LibraryComponentGroups {
  /** The list of component groups provided by the package. */
  newGroups?: LibraryComponentGroup[];

  /** The list of component groups that this package extends.*/
  extendedGroups?: LibraryComponentGroup[];
}
```

### `LibraryComponentGroup`

The component group provided by a library.

```typescript
interface LibraryComponentGroup {
  /** The fully qualified library name. A string consisting of a namespace and
   * a library name separated by a dot `<namespace>.<library name>`,
   * e.g. `Standard.Base`. */
  library: string;
  /** The group name without the library name prefix.
   *  e.g. given the `Standard.Base.Group 1` group reference,
   * the `name` field contains `Group 1`. */
  name: string;
  color?: string;
  icon?: string;
  /** A list of components provided by this component group. */
  exports: LibraryComponent[];
}
```

### `LibraryComponent`

A single component of a component group.

```typescript
interface LibraryComponent {
  /** The component name. */
  name: string;
  /** The component shortcut. */
  shortcut?: string;
}
```

## Connection Management

In order to properly set-up and tear-down the language server connection, we
need a set of messages to control this process.

### `session/initProtocolConnection`

This message initializes the connection used to send the textual protocol
messages. This initialization is important such that the client identifier can
be correlated between the textual and data connections.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface SessionInitProtocolConnectionParameters {
  clientId: UUID;
}
```

#### Result

```typescript
interface SessionInitProtocolConnectionResult {
  contentRoots: ContentRoot[];
}
```

#### Errors

- [`SessionAlreadyInitialisedError`](#sessionalreadyinitializederror) to signal
  that the session is already initialized.
- [`ResourcesInitializationError`](#resourcesinitializationerror) to signal
  about the error during the initialization of Language Server resources.

### `session/initBinaryConnection`

This message initializes the data connection used for transferring binary data
between engine and clients. This initialization is important such that the
client identifier can be correlated between the data and textual connections.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Data
- **Visibility:** Public

#### Parameters

```csharp
namespace org.enso.languageserver.protocol.binary;

// A command initializing a data session.
table InitSessionCommand {
  // A unique identifier of a client initializing the session.
  identifier: EnsoUUID (required);
}

root_type InitSessionCommand;
```

#### Result

```csharp
namespace org.enso.languageserver.protocol.binary;

// Indicates an operation has succeeded.
table Success {}
```

#### Errors

None

## Capability Management

In order to mediate between multiple clients properly, the language server has a
robust notion of capability management to grant and remove permissions from
clients.

### `capability/acquire`

This requests that the server grant the specified capability to the requesting
client.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface CapabilityAcquireParameters {
  method: string;
  registerOptions?: any;
}
```

The `registerOptions` are determined by the `method`. The method must be listed
in the section on [capabilities](#capabilities) below.

#### Result

```typescript
type CapabilityAcquireResult = null;
```

#### Errors

TBC

### `capability/release`

This requests that the server acknowledge that the client is releasing a given
capability.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface CapabilityReleaseParameters {
  registration: CapabilityRegistration;
}
```

#### Result

```typescript
type CapabilityReleaseResult = null;
```

#### Errors

TBC

### `capability/granted`

This notifies the client that it has been granted a capability without any
action on its part.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface CapabilityGrantedNotification {
  registration: CapabilityRegistration;
}
```

### `capability/forceReleased`

This notifies the client that a capability has been forcibly removed from its
capability set.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface CapabilityForceReleasedNotification {
  registration: CapabilityRegistration;
}
```

#### Errors

TBC

## Capabilities

The capability management features work with the following capabilities.

### `text/canEdit`

This capability states that the capability has the ability to perform both
`text/applyEdit` and `text/save` for the specified file.

- **method:** `text/canEdit`
- **registerOptions:** `{path: Path;}`

#### Enables

- [`text/applyEdit`](#textapplyedit)
- [`text/save`](#textsave)

#### Disables

None

### `file/receivesTreeUpdates`

This capability states that the client will receive updates for any watched
content roots in the current project.

- **method:** `file/receivesTreeUpdates`
- **registerOptions:** `{ path: Path; }`

#### Enables

- [`file/event`](#fileevent)

#### Disables

None

#### Errors

[`capability/acquire`](#capabilityacquire):

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`FileNotFound`](#filenotfound) informs that path cannot be found.

[`capability/release`](#capabilityrelease):

- [`CapabilityNotAcquired`](#capabilitynotacquired) informs that requested
  capability is not acquired.

### `executionContext/canModify`

This capability states that the client has the ability to modify an execution
context, including modifying the execution stack, invalidating caches, or
destroying the context.

- **method:** `executionContext/canModify`
- **registerOptions:** `{ contextId: ContextId; }`

#### Enables

- [`executionContext/destroy`](#executioncontextdestroy)
- [`executionContext/recompute`](#executioncontextrecompute)
- [`executionContext/interrupt`](#executioncontextinterrupt)
- [`executionContext/push`](#executioncontextpush)
- [`executionContext/pop`](#executioncontextpop)
- [`executionContext/executeExpression`](#executioncontextexecuteexpression)
- [`executionContext/attachVisualization`](#executioncontextattachvisualization)
- [`executionContext/modifyVisualization`](#executioncontextmodifyvisualization)
- [`executionContext/detachVisualization`](#executioncontextdetachvisualization)
- [`executionContext/visualizationUpdate`](#executioncontextvisualizationupdate)
- [`executionContext/visualizationEvaluationFailed`](#executioncontextvisualizationevaluationfailed)

#### Disables

None

### `executionContext/receivesUpdates`

This capability states that the client receives expression value updates from a
given execution context.

- **method:** `executionContext/receivesUpdates`
- **registerOptions:** `{ contextId: ContextId; }`

#### Enables

- [`executionContext/expressionUpdates`](#executioncontextexpressionupdates)
- [`executionContext/executionFailed`](#executioncontextexecutionfailed)
- [`executionContext/executionStatus`](#executioncontextexecutionstatus)

#### Disables

None

### `search/receivesSuggestionsDatabaseUpdates`

This capability states that the client receives the search database updates for
a given execution context.

- **method:** `search/receivesSuggestionsDatabaseUpdates`
- **registerOptions:** `{}`

#### Enables

- [`search/suggestionsDatabaseUpdate`](#suggestionsdatabaseupdate)
- [`search/suggestionsOrderDatabaseUpdate`](#suggestionsorderdatabaseupdate)

#### Disables

None

## File Management Operations

The language server also provides file operations to the IDE.

### `file/write`

This requests that the file manager component write to a specified file with the
specified contents.

- **Type:** Request
- **Direction:** Client -> Server

This request is _explicitly_ allowed to write to files that do not exist, and
will create them under such circumstances. If a file is recorded as 'open' by
one of the clients, and another client attempts to write to that file, the write
must fail.

#### Parameters

```typescript
interface FileWriteParameters {
  path: Path;
  contents: FileContents<T>;
}
```

#### Result

```typescript
type FileWriteResult = null;
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`AccessDeniedError`](#accessdeniederror) to signal that a user doesn't have
  access to a resource.

### `file/read`

This requests that the file manager component reads the contents of a specified
file.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

If the file is recorded as open by the language server, then the result will
return the contents from the in-memory buffer rather than the file on disk.

#### Parameters

```typescript
interface FileReadParameters {
  path: Path;
}
```

#### Result

```typescript
interface FileReadResult {
  contents: FileContents<T>;
}
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`AccessDeniedError`](#accessdeniederror) to signal that a user doesn't have
  access to a resource.
- [`FileNotFound`](#filenotfound) informs that file cannot be found.

### `file/writeBinary`

This requests that the file manager component write to a specified file with the
binary contents.

- **Type:** Request
- **Connection:** Binary
- **Direction:** Client -> Server

This request is _explicitly_ allowed to write to files that do not exist, and
will create them under such circumstances. If a file is recorded as 'open' by
one of the clients, and another client attempts to write to that file, the write
must fail.

#### Parameters

```csharp
namespace org.enso.languageserver.protocol.binary;

// A command writing binary contents to a file.
table WriteFileCommand {
  // A path to a file.
  path: Path;
  // Binary contents.
  contents: [ubyte];
}

```

#### Result

```csharp
namespace org.enso.languageserver.protocol.binary;

// Indicates an operation has succeeded.
table Success {}
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`AccessDeniedError`](#accessdeniederror) to signal that a user doesn't have
  access to a resource.

### `file/readBinary`

This requests that the file manager component reads the binary contents of a
specified file.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Binary
- **Visibility:** Public

If the file is recorded as open by the language server, then the result will
return the contents from the in-memory buffer rather than the file on disk.

#### Parameters

```csharp
namespace org.enso.languageserver.protocol.binary;

// A command reading binary contents from a file.
table ReadFileCommand {
  // A path to a file.
  path: Path;
}
```

#### Result

```csharp
namespace org.enso.languageserver.protocol.binary;

// A reply for a ReadFileCommand.
table FileContentsReply {
  // Binary contents.
  contents: [ubyte];
}
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`AccessDeniedError`](#accessdeniederror) to signal that a user doesn't have
  access to a resource.
- [`FileNotFound`](#filenotfound) informs that file cannot be found.

### `file/writeBytes`

This requests that the file manager component writes a set of bytes to the
specified file at the specified offset.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Binary
- **Visibility:** Public

This method will create a file if no file is present at `path`.

- The `overwriteExisting` boolean should be set if `byteOffset` is less than the
  length of the file.
- The `byteOffset` property is zero-indexed. To append to the file you begin
  writing at index `file.length`.
- If `byteOffset` is less than the length of the file and `overwriteExisting` is
  set, it will truncate the file to length `byteOffset + bytes.length`.
- If `byteOffset > file.length`, the bytes in the range
  `[file.length, byteOffset)` will be filled with null bytes. Please note that,
  in this case, the checksum in the response will also be calculated on the null
  bytes.

#### Parameters

```csharp
namespace org.enso.languageserver.protocol.binary;

table WriteBytesCommand {
  // The file to write to.
  path: Path (required);
  // The byte offset in the file to write from.
  byteOffset: ulong;
  // Whether existing content should be overwritten.
  overwriteExisting: bool;
  // The file contents.
  bytes: [ubyte] (required);
}
```

#### Result

```csharp
namespace org.enso.languageserver.protocol.binary;

table WriteBytesReply {
  // The checksum of the written bytes.
  checksum: EnsoDigest (required);
}
```

Notes:

- The `checksum` is only of the `bytes` in the request as they were written to
  disk. This does _not_ include checksumming the entire file. For that, please
  see [`file/checksumBytes`](#file-checksumbytes).

#### Errors

- [`CannotOverwrite`](#cannotoverwrite) to signal that an overwrite would be
  necessary to perform the operation but that `overwriteExisting` is not set.
- [`NotFile`](#notfile) if the provided `segment.path` is not a file.

### `file/readBytes`

Asks the language server to read the specified number of bytes at the specified
offset in the file.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Binary
- **Visibility:** Public

It will attempt to read _as many as_ `segment.length` bytes, but does not
guarantee that the response will contain `segment.length` bytes (e.g. if
`segment.length` would require reading off the end of the file).

#### Parameters

```csharp
namespace org.enso.languageserver.protocol.binary;

table ReadBytesCommand {
  // The segment in a file to read bytes from.
  segment: FileSegment (required);
}
```

#### Result

```csharp
namespace org.enso.languageserver.protocol.binary;

table ReadBytesReply {
  // The checksum of the bytes in this response.
  checksum: EnsoDigest (required);
  // The requested file contents.
  bytes: [ubyte] (required);
}
```

Notes:

- The `checksum` is of the `bytes` as they have been read from disk.

#### Errors

- [`FileNotFound`](#filenotfound) if the file at `segment.path` does not exist.
- [`ReadOutOfBounds`](#readoutofbounds) if `segment.byteOffset` is not present
  in the file at `segment.path`.
- [`NotFile`](#notfile) if the provided `segment.path` is not a file.

### `file/create`

This request asks the file manager to create the specified file system object.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

This will fail if the specified object already exists.

#### Parameters

```typescript
interface FileCreateParameters {
  object: FileSystemObject;
}
```

#### Result

```typescript
type FileCreateResult = null;
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`AccessDeniedError`](#accessdeniederror) to signal that a user doesn't have
  access to a resource.

### `file/delete`

This request asks the file manager to delete the specified file system object.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface FileCreateParameters {
  path: Path;
}
```

#### Result

```typescript
type FileDeleteResult = null;
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`FileNotFound`](#filenotfound) informs that file cannot be found.
- [`FileExists`](#fileexists) informs that file already exists

### `file/copy`

This request asks the file manager to copy a specified filesystem object to
another location.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface FileCopyParameters {
  from: Path;
  to: Path;
}
```

#### Result

```typescript
type FileCopyResult = null;
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`FileNotFound`](#filenotfound) informs that file cannot be found.

### `file/move`

This request asks the file manager to move a specified filesystem object to
another location.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

The move should be specified by filesystem events, and such notifications should
inform the client that the currently edited file has been moved.

#### Parameters

```typescript
interface FileMoveParameters {
  from: Path;
  to: Path;
}
```

#### Result

```typescript
type FileMoveResult = null;
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`FileNotFound`](#filenotfound) informs that file cannot be found.
- [`FileExists`](#fileexists) informs that target file already exists.

### `file/exists`

This request asks the file manager to check whether a filesystem object exists
at the specified path.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface FileExistsParameters {
  path: Path;
}
```

#### Result

```typescript
interface FileExistsResult {
  exists: boolean;
}
```

#### Errors

- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.

### `file/tree`

This request asks the file manager component to generate and provide the
directory tree starting at a given path.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface FileTreeParameters {
  path: Path;
  depth?: number;
}
```

#### Result

```typescript
interface FileTreeResult {
  tree: DirectoryTree;
}
```

#### Errors

- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`FileNotFound`](#filenotfound) informs that requested path does not exist or
  provided depth argument is <= 0.
- [`NotDirectory`](#notdirectory) informs that requested path is not a
  directory.

### `file/list`

This request lists the contents of a given filesystem object. For a file it will
just return the file, while for a directory it will list the contents of the
directory.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface FileListParameters {
  path: Path;
}
```

#### Result

```typescript
interface FileListResult {
  paths: FileSystemObject[];
}
```

#### Errors

- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`FileNotFound`](#filenotfound) informs that requested path does not exist.
- [`NotDirectory`](#notdirectory) informs that requested path is not a
  directory.

### `file/info`

This request gets information about a specified filesystem object.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

This request should work for all kinds of filesystem object.

#### Parameters

```typescript
interface FileInfoParameters {
  path: Path;
}
```

#### Result

```typescript
interface FileInfoResult {
  attributes: FileAttributes;
}
```

#### Errors

- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`FileNotFound`](#filenotfound) informs that requested path does not exist.

### `file/checksum`

Requests that the language server provide the checksum of the provided file.
Only defined when the provided `path` is a file.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

It calculates the checksum of the entire file.

#### Parameters

```typescript
interface ChecksumRequest {
  /** The path to the file to get the checksum for. */
  path: Path;
}
```

#### Result

```typescript
interface ChecksumResponse {
  /** The checksum of the file at `path`. */
  checksum: SHA3_224;
}
```

#### Errors

- [`FileNotFound`](#filenotfound) if the file at `path` does not exist.
- [`NotFile`](#notfile) if the provided `path` does not point to a file.

### `file/checksumBytes`

Requests that the language server provides the checksum of the provided byte
range.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Binary
- **Visibility:** Public

#### Parameters

```csharp
namespace org.enso.languageserver.protocol.binary;

table ChecksumBytesCommand {
  // The segment in a file to checksum.
  segment : FileSegment (required);
}
```

#### Result

```csharp
namespace org.enso.languageserver.protocol.binary;

table ChecksumBytesReply {
  // The segment in a file to checksum.
  checksum : EnsoDigest;
}
```

#### Errors

- [`FileNotFound`](#filenotfound) if the file at `segment.path` does not exist.
- [`ReadOutOfBounds`](#readoutofbounds) if `segment.byteOffset` is not present
  in the file at `segment.path`, or if `segment.length` does not fit within the
  file.
- [`NotFile`](#notfile) if the provided `segment.path` is not a file.

### `file/event`

This is a notification that is sent every time something under a watched content
root changes. It is used to ensure that the client's filesystem representation
stays in synchronisation with reality.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

Events should be sent from server to client for every event observed under one
of the (possibly multiple) content roots.

#### Notification

```typescript
interface FileEventNotification {
  path: Path;
  kind: FileEventKind;
}
```

### `file/rootAdded`

This is a notification sent to all clients to inform them that a content root
has been added.

At the beginning, a series of notifications is sent that lists all content roots
that are present at the current moment. This message may contain the same
content roots that were already present in the `session/initProtocolConnection`.
That is done, because there is no guarantee that no root has been added between
the init message and the time when notifications start being sent, and this
ensures that no content root is missed.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface FileRootAddedNotification {
  root: ContentRoot;
}
```

### `file/rootRemoved`

This is a notification sent to all clients other than the one performing the
removal of the content root in order to inform them of the removal of the root.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface FileRootRemovedNotification {
  /** The ID of the content root. */
  id: UUID;
}
```

## Version Control System Operations

The language server has a set of version control operations to keep track of
changes made to the projects.

### `vcs/init`

This requests that the VCS manager component initializes version control for the
project identified by the root directory.

- **Type:** Request
- **Direction:** Client -> Server

This request assumes that no prior VCS is present for the project at a specified
location. If VCS has already been initialized once, the operation will fail.

#### Parameters

```typescript
interface VCSInitParameters {
  root: Path;
}
```

#### Result

```typescript
type VCSInitResult = null;
```

#### Errors

- [`VCSError`](#vcserror) to signal a generic, unrecoverable VCS error.
- [`ProjectNotFound`](#projectnotfounderror) to signal that the requested
  project does not exist
- [`VCSAlreadyPresent`](#vcsalreadyexistserror) to signal that the requested
  project does not exist

### `vcs/save`

This requests that the VCS manager component record any changes made to the
project, compared to the last save.

- **Type:** Request
- **Direction:** Client -> Server

This request assumes that the project at a specified location exists and VCS has
been initialized for it with `vcs/init` operation. If the project is not under
Enso's version control system, the operation must fail. If no changes have been
recorded since the last save, the operation must still succeed. All saves
include a timestamp when the request was made. For easier identification, the
request has an optional `name` parameter that will prefix the timestamp.

#### Parameters

```typescript
interface VCSSaveParameters {
  root: Path;
  name?: string;
}
```

#### Result

```typescript
interface VCSSaveResult {
  commitId: string;
  message: string;
}
```

#### Errors

- [`VCSError`](#vcserror) to signal a generic, unrecoverable VCS error.
- [`ProjectNotFound`](#projectnotfounderror) to signal that the requested
  project does not exist
- [`VCSNotFound`](#vcsnotfounderror) to signal that the project is not under
  Enso's version control

### `vcs/status`

This requests that the VCS manager component report the current status of the
changes made to the project.

- **Type:** Request
- **Direction:** Client -> Server

This request assumes that the project at a specified location exists and VCS has
been initialized for it with `vcs/init` operation. If the project is not under
Enso's version control system, the operation must fail. The status of the
project includes:

- `dirtty` flag, indicating if any of the project files has been modified, added
  or deleted
- list of paths to the modified files, if any
- the metadata of a last save, if any

#### Parameters

```typescript
interface VCSStatusParameters {
  root: Path;
}
```

#### Result

```typescript
interface VCSStatusResult {
  dirty: boolean;
  changed: Path[];
  lastSave: {
    commitId: string;
    message: string;
  };
}
```

#### Errors

- [`VCSError`](#vcserror) to signal a generic, unrecoverable VCS error.
- [`ProjectNotFound`](#projectnotfounderror) to signal that the requested
  project does not exist
- [`VCSNotFound`](#vcsnotfounderror) to signal that the project is not under
  Enso's version control

### `vcs/restore`

This requests that the VCS manager component restores the project to a past
state recorded in Enso's VCS. All unsaved changes will be lost.

- **Type:** Request
- **Direction:** Client -> Server

This request assumes that the project at a specified location exists and VCS has
been initialized for it with `vcs/init` operation. If the project is not under
Enso's version control system, the operation must fail.

The request has an optional `commitId` parameter that refers to the past
checkpoint recorded with `vcs/save`. If no save exists with a provided
`commitId`, the request must fail. If no `commitId` exists, the operation will
restore the project to the last saved state, will all current modifications
forgotten.

If the contents of any open buffer has changed as a result of this operation,
all subscribed clients will be notified about the new version of the file via
`text/didChange` push notification.

A file might have been removed during the operation while there were still open
buffers for that file. Any such clients will be modified of a file removal via
the `file/event` notification.

The result of the call returns a list of files that have been modified during
the operation.

#### Parameters

```typescript
interface VCSRestoreParameters {
  root: Path;
  commitId?: string;
}
```

#### Result

```typescript
interface VCSRestoreResult {
  changed: Path[];
}
```

#### Errors

- [`VCSError`](#vcserror) to signal a generic, unrecoverable VCS error.
- [`ProjectNotFound`](#projectnotfounderror) to signal that the requested
  project does not exist
- [`VCSNotFound`](#vcsnotfounderror) to signal that the project is not under
  Enso's version control
- [`SaveNotFound`](#savenotfounderror) to signat that the requested save could
  not be identified in the project's version control

### `vcs/list`

This requests that the VCS manager component returns a list of project's saves.

- **Type:** Request
- **Direction:** Client -> Server

By default, the operation will return all project's saves. An optional `limit`
parameter will ensure that only the last `limti` ones are reported.

This request assumes that the project at a specified location exists and VCS has
been initialized for it with `vcs/init` operation. If the project is not under
Enso's version control system, the operation must fail.

#### Parameters

```typescript
interface VCSListParameters {
  root: Path;
  limit?: number;
}
```

#### Result

```typescript
interface VCSListResult {
  saves: [
    {
      commitId: string;
      message: string;
    },
  ];
}
```

#### Errors

- [`VCSError`](#vcserror) to signal a generic, unrecoverable VCS error.
- [`ProjectNotFound`](#projectnotfounderror) to signal that the requested
  project does not exist
- [`VCSNotFound`](#vcsnotfounderror) to signal that the project is not under
  Enso's version control

## Text Editing Operations

The language server also has a set of text editing operations to ensure that it
stays in sync with the clients.

### `text/openFile`

This requests the language server to open the specified file.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

If no client has write lock on the opened file, the capability is granted to the
client that sent the `text/openFile` message.

#### Parameters

```typescript
interface TextOpenFileParameters {
  path: Path;
}
```

#### Result

```typescript
interface TextOpenFileResult {
  writeCapability?: CapabilityRegistration;
  content: string;
  currentVersion: SHA3_224;
}
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`AccessDeniedError`](#accessdeniederror) to signal that a user doesn't have
  access to a resource.
- [`FileNotFound`](#filenotfound) informs that file cannot be found.

### `text/openBuffer`

This requests the language server to open a specified in-memory buffer mapped to
the provided path. If the path exists, this command behaves the same as
[`text/openFile`](#textopenfile). If the path does not exist, the command
creates empty in-memory buffer for the provided path.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

If no client has write lock on the opened file, the capability is granted to the
client that sent the `text/openBuffer` message. The in-memory buffers can be
used to define hidden modules with visualization functions. In a nutshell, the
request behaves the same as [`text/openFile`](#textopenfile) but does not
require the file to exist.

#### Parameters

```typescript
interface TextOpenBufferParameters {
  path: Path;
}
```

#### Result

```typescript
interface TextOpenBufferResult {
  writeCapability?: CapabilityRegistration;
  content: string;
  currentVersion: SHA3_224;
}
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`AccessDeniedError`](#accessdeniederror) to signal that a user doesn't have
  access to a resource.

### `text/closeFile`

This requests the language server to close the specified file.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

Any pending changes to files will be saved before closing the file.

#### Parameters

```typescript
interface TextCloseFileParameters {
  path: Path;
}
```

#### Result

```typescript
type TextOpenFileResult = null;
```

#### Errors

- [`FileNotOpenedError`](#filenotopenederror) to signal that a file wasn't
  opened.

### `text/save`

This requests for the language server to save the specified file.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

The request may fail if the requesting client does not have permission to edit
that file, or if the client is requesting a save of an outdated version. Note
that language-server autosaves changes to the file, making this operation
obsolete.

#### Parameters

```typescript
interface TextSaveParameters {
  path: Path;
  currentVersion: SHA3 - 224;
}
```

#### Result

```typescript
type TextSaveResult = null;
```

#### Errors

- [`FileNotOpenedError`](#filenotopenederror) to signal that the file isn't
  open.
- [`InvalidVersionError`](#invalidversionerror) to signal that the version
  provided by the client doesn't match the version computed by the server.
- [`WriteDeniedError`](#writedeniederror) to signal that the client doesn't hold
  write lock for the buffer.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.
- [`ContentRootNotFoundError`](#contentrootnotfounderror) to signal that the
  requested content root cannot be found.
- [`AccessDeniedError`](#accessdeniederror) to signal that the user doesn't have
  access to a resource.

### `text/applyEdit`

This requests that the server apply a series of edits to the project. These
edits solely concern text files.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

This operation may fail if the requesting client does not have permission to
edit the resources for which edits are sent. This failure _may_ be partial, in
that some edits are applied and others are not.

#### Parameters

```typescript
interface TextApplyEditParameters {
  /** The file edit. */
  edit: FileEdit;
  /** A flag indicating whether we should re-execute the program after applying
   * the edit. Default value is `true`, indicating the program should be
   * re-executed. */
  execute?: boolean;
}
```

#### Result

```typescript
type TextApplyEditResult = null;
```

#### Errors

- [`FileNotOpenedError`](#filenotopenederror) to signal that the file isn't
  open.
- [`TextEditValidationError`](#texteditvalidationerror) to signal that
  validation has failed for a series of edits.
- [`InvalidVersionError`](#invalidversionerror) to signal that the version
  provided by the client doesn't match the version computed by the server.
- [`WriteDeniedError`](#writedeniederror) to signal that the client doesn't hold
  write lock for the buffer.

### `text/applyExpressionValue`

This requests to set an expression to a new value. For example, it can update a
literal value, like changing `98` to `99`, `true` to `false` or `"Hello"` to
`"World!"`. This method is a more specific version of
[`text/applyEdit`](#textapplyedit) and guarantees that the syntax tree is not
changed after applying the edit. This way the engine can perform a more
efficient value swap instead of reparsing and recompiling the whole module.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

This operation may fail if the requesting client does not have permission to
edit the resources for which edits are sent.

#### Parameters

```typescript
interface TextApplyExpressionValueParameters {
  /** The expression id to update. */
  expressionId: ExpressionId;
  /** The path to the file. */
  path: Path;
  /** The file edit containing the new expression value. */
  edit: TextEdit;
  /** The current version of the buffer. */
  oldVersion: SHA3_224;
  /** The version of the buffer after applying the edit. */
  newVersion: SHA3_224;
}
```

#### Result

```typescript
type TextApplyExpressionValueResult = null;
```

#### Errors

- [`FileNotOpenedError`](#filenotopenederror) to signal that the file isn't
  open.
- [`TextEditValidationError`](#texteditvalidationerror) to signal that
  validation has failed for this edit.
- [`InvalidVersionError`](#invalidversionerror) to signal that the version
  provided by the client doesn't match the version computed by the server.
- [`WriteDeniedError`](#writedeniederror) to signal that the client doesn't hold
  write lock for the buffer.

### `text/didChange`

This is a notification sent from the server to the clients to inform them of any
changes made to files that they have open.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

This notification must _only_ be sent for files that the client has open.

#### Notification

```typescript
interface TextDidChangeNotification {
  edits: FileEdit[];
}
```

### `text/autoSave`

This is a notification sent from the server to the clients to inform them of any
successful auto-save action.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

This notification must _only_ be sent for files that the client has open.

#### Notification

```typescript
interface TextAutoSaveNotification {
  path: Path;
}
```

### `text/fileModifiedOnDisk`

This is a notification sent from the server to the clients to inform them that
the file was modified on disk by an external editor.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface TextFileModifiedOnDiskNotification {
  path: Path;
}
```

## Workspace Operations

The language server also has a set of operations useful for managing the client
workspace.

### `workspace/projectInfo`

This request allows the IDE to request information about the currently open
project in situations where it does not have a project manager to connect to.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface WorkspaceProjectInfoParameters {}
```

#### Result

```typescript
interface WorkspaceProjectInfoResult {
  /** The name of the project. */
  projectName: string;
  /** The engine version on which the project is running. */
  engineVersion: string;
  /** The version of graal on which the project is running. */
  graalVersion: string;
}
```

#### Errors

- [`CannotDecode`](#cannotdecode) if the project configuration cannot be
  decoded.
- [`FileNotFound`](#filenotfound) if the project configuration cannot be found.

## Monitoring

The language server also has a heartbeat operation to monitor the Language
server. This API is private and should be used only by the Project Manager.

### `heartbeat/ping`

This request is sent from the supervisor process to the server to check the
health of the Language Server.

- **Type:** Request
- **Direction:** Supervisor -> Server
- **Connection:** Protocol
- **Visibility:** Private

#### Parameters

```typescript
interface HeartbeatPingParameters {}
```

#### Result

```typescript
type HeartbeatPingResult = null;
```

#### Errors

None

### `heartbeat/init`

This request is sent from the bootloader to check if the started language server
instance has finished initialization. A reply should only be sent when the main
module has been fully initialized.

- **Type:** Request
- **Direction:** Supervisor -> Server
- **Connection:** Protocol
- **Visibility:** Private

#### Parameters

```typescript
interface HeartbeatInitParameters {}
```

#### Result

```typescript
type HeartbeatInitResult = null;
```

#### Errors

None

## Refactoring

The language server also provides refactoring operations to restructure an
internal body of code.

### `refactoring/renameProject`

This request is sent from the project manager to the server to refactor project
name in an interpreter runtime.

- **Type:** Request
- **Direction:** Project Manager -> Server
- **Connection:** Protocol
- **Visibility:** Private

#### Parameters

```typescript
interface RefactoringRenameProjectParameters {
  namespace: string;
  oldName: string;
  newName: string;
}
```

#### Result

```typescript
type RefactoringRenameProjectResult = null;
```

#### Errors

None

### `refactoring/renameSymbol`

Sent from the client to the server to rename a symbol in the program. The text
edits required to perform the refactoring will be returned as a
[`text/didChange`](#textdidchange) notification.

- **Type:** Request
- **Direction:** Project Manager -> Server
- **Connection:** Protocol
- **Visibility:** Private

#### Supported refactorings

Refactorins supports only limited cases listed below.

##### Local definition

```rust
main =
    operator1 = 42
    ^^^^^^^^^
```

Expression id in the request should point to the left hand side symbol of the
assignment.

##### Module method

```rust
function1 x = x
^^^^^^^^^

main =
    operator1 = Main.function1 42
```

Expression id in the request should point to the symbol defining the function.

Current limitations of the method renaming are:

- Methods defined on types are not supported, i.e.
  ```rust
  Main.function1 x = x
  ```
- Method calls where the self type is not specified will not be renamed, i.e.

  ```rust
  function1 x = x

  main =
      operator1 = function1 42
  ```

#### Parameters

```typescript
interface RefactoringRenameSymbolParameters {
  /** The qualified module name. */
  module: string;
  /** The symbol to rename. */
  expressionId: ExpressionId;
  /** The new name of the symbol. If the provided name is not a valid Enso
   * identifier (contains unsupported symbols, spaces, etc.), it will be normalized.
   * The final name will be returned in the response. */
  newName: string;
}
```

#### Result

```typescript
interface RefactoringRenameSymbolResult {
  newName: string;
}
```

#### Errors

- [`ModuleNotFoundError`](#modulenotfounderror) to signal that the requested
  module cannot be found.
- [`ExpressionNotFoundError`](#expressionnotfounderror) to signal that the given
  expression cannot be found.
- [`FailedToApplyEdits`](#failedtoapplyedits) to signal that the refactoring
  operation was not able to apply generated edits.
- [`RefactoringNotSupported`](#refactoringnotsupported) to signal that the
  refactoring of the given expression is not supported.

### `refactoring/projectRenamed`

This is a notification sent from the server to the clients to inform them about
the new project name.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface RefactoringProjectRenamedNotification {
  /** Old normalized name of the project. */
  oldNormalizedName: string;
  /** New normalized name of the prject. */
  newNormalizedName: string;
  /** New display name of the project. */
  newName: string;
}
```

## Execution Management Operations

The execution management portion of the language server API deals with exposing
fine-grained control over program and expression execution to the clients of the
language server. This is incredibly important for enabling the high levels of
interactivity required by Enso Studio.

### Execution Management Example

Given the default project structure.

```text
├── package.yaml
└── src
    └── Main.enso
```

```bash
$ cat src/Main.enso

main =
    x = 6
    y = x.foo 5
    z = y + 5
    z

Number.foo = x ->
    y = this + 3
    z = y * x
    z



#### METADATA ####
[[{"index": {"value": 98}, "size": {"value": 5}}, "5fc0c11d-bd83-4ca3-b847-b8e362f7658c"],[{"index": {"value": 81}, "size": {"value": 8}}, "1cda3676-bd62-41f8-b6a1-a1e1b7c73d18"],[{"index": {"value": 42}, "size": {"value": 5}}, "899a11e5-4d2b-43dc-a867-2f2ef2d2ba62"],[{"index": {"value": 26}, "size": {"value": 7}}, "37f284d4-c593-4e65-a4be-4948fbd2adfb"],[{"index": {"value": 16}, "size": {"value": 1}}, "c553533e-a2b9-4305-9f12-b8fe7781f933"]]
[]
```

Notice extra newline in the beginning of the `Main.enso` file, it is important
for the precalculated metadata indexes.

### Create Execution Context

```json
{
  "jsonrpc": "2.0",
  "method": "executionContext/create",
  "id": 0,
  "params": null
}
```

Return capabilities together with a newly created `ContextId`.

```json
{
  "jsonrpc": "2.0",
  "id": 0,
  "result": {
    "contextId": "1eb5ad04-4094-4c1f-be54-e9d29ddf19a3",
    "canModify": {
      "method": "executionContext/canModify",
      "registerOptions": {
        "contextId": "1eb5ad04-4094-4c1f-be54-e9d29ddf19a3"
      }
    },
    "receivesUpdates": {
      "method": "executionContext/receivesUpdates",
      "registerOptions": {
        "contextId": "1eb5ad04-4094-4c1f-be54-e9d29ddf19a3"
      }
    }
  }
}
```

### Push Item

Entering the `main` method. First item on the stack should always be an
`ExplicitCall`.

```json
{
  "jsonrpc": "2.0",
  "method": "executionContext/push",
  "id": 0,
  "params": {
    "contextId": "1eb5ad04-4094-4c1f-be54-e9d29ddf19a3",
    "stackItem": {
      "type": "ExplicitCall",
      "methodPointer": {
        "file": {
          "rootId": "18f642a2-5f69-4fc8-add6-13bf199ca326",
          "segments": ["src", "Main.enso"]
        },
        "definedOnType": "Main",
        "name": "main"
      },
      "thisArgumentExpression": null,
      "positionalArgumentsExpressions": []
    }
  }
}
```

Returns successful reponse.

```json
{
  "jsonrpc": "2.0",
  "id": 0,
  "result": null
}
```

And a value update, result of the method `foo` call defined on type `Number`.

```json
{
  "jsonrpc": "2.0",
  "method": "executionContext/expressionValuesComputed",
  "params": {
    "contextId": "1eb5ad04-4094-4c1f-be54-e9d29ddf19a3",
    "updates": [
      {
        "id": "37f284d4-c593-4e65-a4be-4948fbd2adfb",
        "type": "Number",
        "shortValue": "45",
        "methodCall": {
          "file": {
            "rootId": "18f642a2-5f69-4fc8-add6-13bf199ca326",
            "segments": ["src", "Main.enso"]
          },
          "definedOnType": "Number",
          "name": "foo"
        }
      }
    ]
  }
}
```

We can go deeper and evaluate the method `foo` call by pushing the `LocalCall`
on the stack. In general, all consequent stack items should be `LocalCall`s.

```json
{
  "jsonrpc": "2.0",
  "method": "executionContext/push",
  "id": 0,
  "params": {
    "contextId": "1eb5ad04-4094-4c1f-be54-e9d29ddf19a3",
    "stackItem": {
      "type": "LocalCall",
      "expressionId": "37f284d4-c593-4e65-a4be-4948fbd2adfb"
    }
  }
}
```

Returns successful response.

```json
{
  "jsonrpc": "2.0",
  "id": 0,
  "result": null
}
```

And update of some value inside the function `foo`.

```json
{
  "jsonrpc": "2.0",
  "method": "executionContext/expressionValuesComputed",
  "params": {
    "contextId": "1eb5ad04-4094-4c1f-be54-e9d29ddf19a3",
    "updates": [
      {
        "id": "1cda3676-bd62-41f8-b6a1-a1e1b7c73d18",
        "type": "Number",
        "shortValue": "9",
        "methodCall": null
      }
    ]
  }
}
```

### Pop Item

```json
{
  "jsonrpc": "2.0",
  "method": "executionContext/pop",
  "id": 0,
  "params": {
    "contextId": "1eb5ad04-4094-4c1f-be54-e9d29ddf19a3"
  }
}
```

Popping one item will return us into the `main` method. Second call will clear
the stack. Subsequent pop calls will result in an error indicating that the
stack is empty.

### `executionContext/create`

Sent from the client to the server to create a new execution context. Return
capabilities [`executionContext/canModify`](#executioncontextcanmodify) and
[`executionContext/receivesUpdates`](#executioncontextreceivesupdates). The
command takes optional `contextId` parameter with the id to create. The command
is idempotent and returns success if the context with provided id already
exists.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextCreateParameters {
  contextId?: ContextId;
}
```

#### Result

```typescript
interface ExecutionContextCreateResult {
  contextId: ContextId;
  canModify: CapabilityRegistration;
  receivesUpdates: CapabilityRegistration;
}
```

#### Errors

None

### `executionContext/destroy`

Sent from the client to the server destroy an execution context and free its
resources.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextDestroyParameters {
  contextId: ContextId;
}
```

#### Result

```typescript
type ExecutionContextDestroyResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.
- [`ContextNotFoundError`](#contextnotfounderror) when context can not be found
  by provided id.

### `executionContext/fork`

Sent from the client to the server to duplicate an execution context, creating
an independent copy, containing all the data precomputed in the first one.
Return capabilities [`executionContext/canModify`](#executioncontextcanmodify)
and [`executionContext/receivesUpdates`](#executioncontextreceivesupdates).

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextForkParameters {
  contextId: ContextId;
}
```

#### Result

```typescript
interface ExecutionContextForkResult {
  contextId: ContextId;
  canModify: CapabilityRegistration;
  receivesUpdates: CapabilityRegistration;
}
```

#### Errors

No known errors.

### `executionContext/push`

Sent from the client to the server execute item and move the execution context
to a new location deeper down the stack. If a stack item becomes invalid because
of a text edit (e.g. the root function of the view was removed), it will stop
executing. If the function reappears, execution should resume as normal.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextPushParameters {
  contextId: ContextId;
  stackItem: StackItem;
}
```

#### Result

```typescript
type ExecutionContextPushResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.
- [`StackItemNotFoundError`](#stackitemnotfounderror) when the request stack
  item could not be found.
- [`InvalidStackItemError`](#invalidstackitemerror) when pushing `LocalCall` on
  top of the empty stack, or pushing `ExplicitCall` on top of non-empty stack.

### `executionContext/pop`

Sent from the client to the server move the execution context up the stack,
corresponding to the client clicking out of the current breadcrumb.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextPopParameters {
  contextId: ContextId;
}
```

#### Result

```typescript
type ExecutionContextPopResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.
- [`EmptyStackError`](#emptystackerror) when the user tries to pop an empty
  stack.

### `executionContext/recompute`

Sent from the client to the server to force recomputation of current position.
May include a list of expressions for which caches should be invalidated.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextRecomputeParameters {
  /** The execution context identifier. */
  contextId: ContextId;
  /** The expressions that will be invalidated before the execution. */
  invalidatedExpressions?: "all" | ExpressionId[];
  /** The execution environment that will be used in the execution. */
  executionEnvironment?: ExecutionEnvironment;
}
```

#### Result

```typescript
type ExecutionContextRecomputeResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.
- [`EmptyStackError`](#emptystackerror) when the user tries to recompute an
  empty stack.

### `executionContext/interrupt`

Sent from the client to the server to interrupt the program execution in the
provided execution context.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextInterruptParameters {
  contextId: ContextId;
}
```

#### Result

```typescript
type ExecutionContextInterruptResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.

### `executionContext/setExecutionEnvironment`

Sent from the client to the server to set the execution context environment.
After setting the environment, the runtime interrupts the current execution,
clears the caches, and schedules execution of the context.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextSetExecutionEnvironmentParameters {
  contextId: ContextId;
  executionEnvironment: ExecutionEnvironment;
}
```

#### Result

```typescript
type ExecutionContextSetExecutionEnvironmentResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.

### `executionContext/getComponentGroups`

Sent from the client to the server to get the list of component groups available
in runtime.

#### Deprecated

The request is deprecated in favor of
[`runtime/getComponentGroups`](#runtimegetcomponentgroups).

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextGetComponentGroupsParameters {
  contextId: ContextId;
}
```

#### Result

```typescript
interface ExecutionContextGetComponentGroupsResult {
  componentGroups: LibraryComponentGroup[];
}
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when context with the provided id
  does not exist.

### `executionContext/expressionUpdates`

Sent from the server to the client to inform about new information for certain
expressions becoming available. Supersedes the
`executionContext/expressionValuesComputed` notification, that will be removed
in future versions.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface ExecutionContextExpressionUpdatesNotification {
  contextId: ContextId;
  updates: ExpressionUpdate[];
}
```

### `executionContext/executionFailed`

Sent from the server to the client to inform about a failure when attempting to
execute a context.

The [`executionContext/executionStatus`](#executioncontextexecutionstatus)
notifies about potential problems in the code found by compiler which did not
prevent the execution from completing successfully. This message signals about
the non-critical errors during runtime, or critical failures in the logic or the
implementation. A critical failure can be a compiler crash, an attempt to
execute an empty stack, an error location a method or a module when issuing a
[`executionContext/push`](#executioncontextpush) command.

`executionContext/executionFailed` and
[`executionContext/executionComplete`](#executioncontextexecutioncomplete)
messages are mutually exclusive, indicating a failed or a successful execution,
respectively.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface ExecutionContextExecutionFailedNotification {
  /** The identifier of the execution context. */
  contextId: ContextId;
  /** The details of the failed execution. */
  result: ExecutionResult;
}
```

### `executionContext/executionComplete`

Sent from the server to the client to inform about the successful execution of a
context.

`executionContext/executionFailed` and
[`executionContext/executionComplete`](#executioncontextexecutioncomplete)
messages are mutually exclusive, indicating a failed or a successful execution,
respectively.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface ExecutionContextExecutionFailedNotification {
  /** The identifier of the execution context. */
  contextId: ContextId;
}
```

### `executionContext/executionStatus`

Sent from the server to the client to inform about a status of execution.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface ExecutionContextExecutionStatusNotification {
  /** The identifier of the execution context. */
  contextId: ContextId;
  /** The list of encountered problems. */
  diagnostics: Diagnostic[];
}
```

### `executionContext/executeExpression`

This message allows the client to execute an arbitrary expression in a context
of a given node. It behaves like putting a breakpoint after the expression with
`expressionId` and executing the provided `expression`. All the local and global
symbols that are available for the `expressionId` will be available when
executing the `expression`. The result of the evaluation will be delivered as a
visualization result on a binary connection. You can think of it as a oneshot
[`executionContext/attachVisualization`](#executioncontextattachvisualization)
visualization request, meaning that the expression will be executed once.

For example, given the current code:

```python
main =
    operator1 = 42
    operator2 = operator1 + 1

fun1 x = x.to_text
```

- You can execute an expression in the context of a function body. In this case,
  the `expressionId` should point to the body of a function. E.g. in the context
  of `main` available symbols are `operator1`, `operator2` and `fun1`.
- Execute expression in the context of a local binding. E.g. in the context of
  `operator2 = operator1 + 1` available symbols are `operator1`, `operator2` and
  `fun1`.
- Execute expression in the context of arbitrary expression. E.g. in the context
  of `operator1 + 1` available symbols are `operator1` and `fun1`.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextExecuteExpressionParameters {
  executionContextId: UUID;
  visualizationId: UUID;
  expressionId: UUID;
  expression: string;
}
```

#### Result

```typescript
type ExecutionContextExecuteExpressionResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.
- [`ContextNotFoundError`](#contextnotfounderror) when context can not be found
  by provided id.
- [`VisualizationExpressionError`](#visualizationexpressionerror) to signal that
  the provided expression cannot be evaluated.

### `executionContext/attachVisualization`

This message allows the client to attach a visualization, potentially
preprocessed by some arbitrary Enso code, to a given node in the program.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextAttachVisualizationParameters {
  visualizationId: UUID;
  expressionId: UUID;
  visualizationConfig: VisualizationConfiguration;
}
```

#### Result

```typescript
type ExecutionContextAttachVisualizationResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.
- [`ContextNotFoundError`](#contextnotfounderror) when context can not be found
  by provided id.
- [`ModuleNotFoundError`](#modulenotfounderror) to signal that the module with
  the visualization cannot be found.
- [`VisualizationExpressionError`](#visualizationexpressionerror) to signal that
  the expression specified in the `VisualizationConfiguration` cannot be
  evaluated.

### `executionContext/detachVisualization`

This message allows a client to detach a visualization from the executing code.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextDetachVisualizationParameters {
  executionContextId: UUID;
  visualizationId: UUID;
  expressionId: UUID;
}
```

#### Result

```typescript
type ExecutionContextDetachVisualizationResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.
- [`ContextNotFoundError`](#contextnotfounderror) when context can not be found
  by provided id.
- [`VisualizationNotFoundError`](#visualizationnotfounderror) when a
  visualization can not be found.

### `executionContext/modifyVisualization`

This message allows a client to modify the configuration for an existing
visualization.

A successful response means that the new visualization configuration has been
applied. In case of an error response, the visualization state does not change.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface ExecutionContextModifyVisualizationParameters {
  visualizationId: UUID;
  visualizationConfig: VisualizationConfiguration;
}
```

#### Result

```typescript
type ExecutionContextModifyVisualizationResult = null;
```

#### Errors

- [`AccessDeniedError`](#accessdeniederror) when the user does not hold the
  `executionContext/canModify` capability for this context.
- [`ContextNotFoundError`](#contextnotfounderror) when context can not be found
  by provided id.
- [`ModuleNotFoundError`](#modulenotfounderror) to signal that the module with
  the visualization cannot be found.
- [`VisualizationExpressionError`](#visualizationexpressionerror) to signal that
  the expression specified in the `VisualizationConfiguration` cannot be
  evaluated.
- [`VisualizationNotFoundError`](#visualizationnotfounderror) when a
  visualization can not be found.

### `executionContext/visualizationUpdate`

This message is responsible for providing a visualization data update to the
client.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Data
- **Visibility:** Public

The `visualizationData` component of the table definition _must_ be
pre-serialized before being inserted into this message. As far as this level of
transport is concerned, it is just a binary blob.

#### Parameters

```csharp
namespace org.enso.languageserver.protocol.binary;

// A visualization context identifying a concrete visualization.
table VisualizationContext {
  // A visualization identifier.
  visualizationId: EnsoUUID (required);
  // A context identifier.
  contextId: EnsoUUID (required);
  // An expression identifier.
  expressionId: EnsoUUID (required);
}

// An event signaling visualization update.
table VisualizationUpdate {
  // A visualization context identifying a concrete visualization.
  visualizationContext: VisualizationContext (required);
  // A visualization data.
  data: [ubyte] (required);
}

root_type VisualizationUpdate;
```

#### Errors

None

### `executionContext/visualizationEvaluationFailed`

Signals that an evaluation of a visualization expression on the computed value
has failed.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface ExecutionContextVisualizationEvaluationFailedNotification {
  /** An execution context identifier. */
  contextId: ContextId;
  /** A visualization identifier. */
  visualizationId: UUID;
  /** An identifier of a visualised expression. */
  expressionId: UUID;
  /** An error message. */
  message: string;
  /** Detailed information about the error. */
  diagnostic?: Diagnostic;
}
```

## Search Operations

Search operations allow requesting for the autocomplete suggestions and search
for the documentation. Search operations return links to the items in the
Suggestions Database instead of returning full entries. Suggestions Database is
a key-value storage with [`SuggestionEntry`](#suggestionentry) values.

### Suggestions Database Example

The following code snippet shows examples of the database entries.

```ruby
type MyType a b

type Maybe
    Nothing
    Just a

    is_just = case this of
        Just _  -> true
        Nothing -> false

foo x =
    10 - x

Number.baz x =
    this + x * 10

main =
    x = foo 42
    y = x.baz x
    IO.println y
```

#### MyType

```typescript
<Constructor>{
  name: "MyType",
  arguments: [],
  returnType: "MyType",
};
```

#### Maybe.Nothing

```typescript
<Constructor>{
  name: "Nothing",
  arguments: [],
  returnType: "Maybe",
};
```

#### Maybe.Just

```typescript
<Constructor>{
  name: "Just",
  arguments: [
    {
      name: "a",
      type: "Any",
      isSuspended: false,
      hasDefault: false,
    },
  ],
  returnType: "Maybe",
};
```

#### Maybe.is_just

```typescript
<Method>{
  name: "is_just",
  arguments: [],
  selfType: "Maybe",
  returnType: "Bool",
};
```

#### foo

```typescript
<Function>{
  name: "foo",
  arguments: [
    {
      name: "x",
      type: "Number",
      isSuspended: false,
      hasDefault: false,
    },
  ],
  returnType: "Bool",
};
```

#### Number.baz

```typescript
<Method>{
  name: "baz",
  arguments: [
    {
      name: "x",
      type: "Number",
      isSuspended: false,
      hasDefault: false,
    },
  ],
  selfType: "Number",
  returnType: "Number",
};
```

#### Local x

```typescript
<Local>{
  name: "x",
  returnType: "Number",
};
```

#### Local y

```typescript
<Local>{
  name: "y",
  returnType: "Number",
};
```

### `search/getSuggestionsDatabase`

Sent from client to the server to receive the full suggestions database.

#### Deprecated

The request always returns empty `entries` field with the correct
`currentVersion`. The suggestions are sent during the initial project
compilation as a part of
[`search/suggestionsDatabaseUpdate`](#searchsuggestionsdatabaseupdate)
notification.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface SearchGetSuggestionsDatabaseParameters {}
```

#### Result

```typescript
interface SearchGetSuggestionsDatabaseResult {
  /** The list of suggestions database entries. */
  entries: SuggestionsDatabaseEntry[];
  /** The version of the received suggestions database. */
  currentVersion: number;
}
```

#### Errors

- [`SuggestionsDatabaseError`](#suggestionsdatabaseerror) an error accessing the
  suggestions database
- [`ProjectNotFoundError`](#projectnotfounderror) project is not found in the
  root directory

### `search/invalidateSuggestionsDatabase`

Sent from client to the server to clean the suggestions database resetting the
version.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface SearchInvalidateSuggestionsDatabaseParameters {}
```

#### Result

```typescript
type SearchInvalidateSuggestionsDatabase = null;
```

#### Errors

- [`SuggestionsDatabaseError`](#suggestionsdatabaseerror) an error accessing the
  suggestions database

### `search/getSuggestionsDatabaseVersion`

Sent from client to the server to receive the current version of the suggestions
database.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface SearchGetSuggestionsDatabaseVersionParameters {}
```

#### Result

```typescript
interface SearchGetSuggestionsDatabaseVersionResult {
  /** The version of the suggestions database. */
  currentVersion: number;
}
```

#### Errors

- [`SuggestionsDatabaseError`](#suggestionsdatabaseerror) an error accessing the
  suggestions database
- [`ProjectNotFoundError`](#projectnotfounderror) project is not found in the
  root directory

### `search/suggestionsDatabaseUpdate`

Sent from server to the client to inform abouth the change in the suggestions
database.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface SearchSuggestionsDatabaseUpdateNotification {
  updates: SuggestionsDatabaseUpdate[];
  currentVersion: number;
}
```

### `search/suggestionsOrderDatabaseUpdate`

Sent from server to the client to inform abouth the change in the suggestions
order database.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface SearchSuggestionsOrderDatabaseUpdateNotification {
  updates: SuggestionsOrderDatabaseUpdate[];
}
```

### `search/completion`

Sent from client to the server to receive the autocomplete suggestion.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface SearchCompletionParameters {
  /** The edited file. */
  file: Path;
  /** The cursor position. */
  position: Position;
  /** Filter by methods with the provided self type. */
  selfType?: string;
  /** Filter by the return type. */
  returnType?: string;
  /** Filter by the suggestion types. */
  tags?: SuggestionEntryType[];
  /** Filter by `static` attribute of method suggestions. */
  isStatic?: boolean;
}
```

#### Result

The identifiers in `results` are guaranteed to be ordered by the specificity of
the type match.

```typescript
interface SearchCompletionResult {
  results: SuggestionId[];
  currentVersion: number;
}
```

#### Errors

- [`SuggestionsDatabaseError`](#suggestionsdatabaseerror) an error accessing the
  suggestions database
- [`ProjectNotFoundError`](#projectnotfounderror) project is not found in the
  root directory
- [`ModuleNameNotResolvedError`](#modulenamenotresolvederror) the module name
  cannot be extracted from the provided file path parameter
- [`SuggestionNotFoundError`](#suggestionnotfounderror) the requested suggestion
  was not found in the suggestions database

## Input/Output Operations

The input/output portion of the language server API deals with redirecting
stdin/stdout/stderr of Enso programs to the clients of the language server. This
is incredibly important for enabling the high levels of interactivity required
by Enso Studio.

### `io/redirectStandardOutput`

This message allows a client to redirect the standard output of Enso programs.
Once the standard output is redirected, the Language server will notify the
client about new output data by emitting `io/standardOutputAppended` messages.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface IORedirectStandardOutputParameters {}
```

#### Result

```typescript
type IORedirectStandardOutput = null;
```

#### Errors

None

### `io/suppressStandardOutput`

This message allows a client to suppress the redirection of the standard output.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface IOSuppressStandardOutputParameters {}
```

#### Result

```typescript
type IOSuppressStandardOutputResult = null;
```

#### Errors

None

### `io/standardOutputAppended`

Sent from the server to the client to inform that new output data are available
for the standard output.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface IOStandardOutputAppendedNotification {
  output: string;
}
```

### `io/redirectStandardError`

This message allows a client to redirect the standard error of Enso programs.
Once the standard error is redirected, the Language server will notify the
client about new output data by emitting `io/standardErrorAppended` messages.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface IORedirectStandardErrorParameters {}
```

#### Result

```typescript
type IORedirectStandardError = null;
```

#### Errors

None

### `io/suppressStandardError`

This message allows a client to suppress the redirection of the standard error.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface IOSuppressStandardErrorParameters {}
```

#### Result

```typescript
type IOSuppressStandardErrorResult = null;
```

#### Errors

None

### `io/standardErrorAppended`

Sent from the server to the client to inform that new output data are available
for the standard error.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
interface IOStandardErrorAppendedNotification {
  output: string;
}
```

### `io/feedStandardInput`

This message allows a client to feed the standard input of Enso programs.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface IOFeedStandardInputParameters {
  input: string;
  isLineTerminated: boolean;
}
```

#### Result

```typescript
type IOFeedStandardInputResult = null;
```

#### Errors

None

### `io/waitingForStandardInput`

Sent from the server to the client to inform that an Enso program is suspended
by `IO.readln`. This message is used to notify a client that she should feed the
standard input.

- **Type:** Notification
- **Direction:** Server -> Client
- **Connection:** Protocol
- **Visibility:** Public

#### Notification

```typescript
type IOWaitingForStandardInputNotification = null;
```

## Library-Related Operations

The library-related operations provide the Language Server with capabilities to
check and modify project's edition settings, list editions published in a given
edition, create local library projects which can be imported in the currently
opened project and publish them.

### `editions/listAvailable`

Lists editions available on the system.

Moreover, if `update` is set to `true`, it will download any new editions from
the repositories and include them in the result as well.

> Currently, if `update` was `true` but some downloads failed, the endpoint will
> still return a success, just containing the editions that were already
> available. In the future it should emit warnings using proper notification
> channels.

#### Parameters

```typescript
interface EditionsListAvailableParameters {
  update: boolean;
}
```

#### Result

```typescript
interface EditionsListAvailableResult {
  editionNames: string[];
}
```

### `editions/resolve`

Resolves settings implied by the edition.

> Currently, it only resolves the engine version, as only it is needed, but
> other settings may be added if necessary.

#### Parameters

```typescript
interface EditionsResolveParameters {
  edition: EditionReference;
}
```

#### Result

```typescript
interface EditionsResolveResult {
  engineVersion: string;
}
```

#### Errors

- [`EditionNotFoundError`](#editionnotfounderror) indicates that the requested
  edition, or some edition referenced in the ancestors of the edition being
  resolved, could not be found.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `editions/getProjectSettings`

Returns the currently set edition-related settings of the project.

Currently, it only returns the parent edition and local library preference. Once
more advanced edition settings are to be supported in the IDE, this endpoint
will be extended.

#### Parameters

```typescript
interface EditionsGetProjectSettingsParameters {}
```

#### Result

```typescript
interface EditionsGetProjectSettingsResult {
  parentEdition?: string;
  preferLocalLibraries: boolean;
}
```

The `parentEdition` may be missing if it is not set. It is possible to manually
edit the `pacakge.yaml` and generate a valid edition config that does not
specify a parent edition.

### `editions/setProjectParentEdition`

Sets the parent edition of the project to a specific edition.

This change may even change the version of the engine associated with the
project, so for the changes to take effect, the language server may need to be
restarted. The endpoint only modifies the `pacakge.yaml` file, which is
preloaded in the Language Server, so it is IDE's responsibility to re-open the
project.

It returns an optional field `needsRestart` which specifies whether the Language
Server needs to be restarted for the change to take effect. If the field is
missing, it should be treated as set to `false`. In the current version it is
always set to `true`.

#### Parameters

```typescript
interface EditionsSetProjectParentEditionParameters {
  newEditionName: string;
}
```

#### Result

```typescript
interface EditionsSetProjectParentEditionResult {
  needsRestart?: boolean;
}
```

#### Errors

- [`EditionNotFoundError`](#editionnotfounderror) indicates that the requested
  edition could not be found.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `editions/setProjectLocalLibrariesPreference`

Sets the `prefer-local-libraries` setting of the project, which specifies if
libraries from `ENSO_HOME/libraries` should take precedence over the ones
defined in the edition.

> This may change which libraries should be loaded in the project. In the future
> it may be possible that this reload could happen dynamically, however
> currently, the language server needs to be restarted (in the same way as for
> `editions/setProjectParentEdition`) for the changes to take effect.

It returns an optional field `needsRestart` which specifies whether the Language
Server needs to be restarted for the change to take effect. If the field is
missing, it should be treated as set to `false`. In the current version it is
always set to `true`.

#### Parameters

```typescript
interface EditionsSetProjectLocalLibrariesPreferenceParameters {
  preferLocalLibraries: boolean;
}
```

#### Result

```typescript
interface EditionsSetProjectLocalLibrariesPreferenceResult {
  needsRestart?: boolean;
}
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `editions/listDefinedLibraries`

Lists all libraries defined in an edition (or all of its parents).

This can be used to display which libraries can be downloaded / added to the
project.

This does not include local libraries not defined explicitly in the project's
edition, even if they can be resolved as per `prefer-local-libraries` setting.
To get local libraries that are not directly referenced in the edition, use
[`library/listLocal`](#librarylistlocal) instead.

#### Parameters

```typescript
interface EditionsListDefinedLibrariesParameters {
  edition: EditionReference;
}
```

#### Result

```typescript
interface EditionsListDefinedLibrariesResult {
  availableLibraries: LibraryEntry[];
}
```

#### Errors

- [`EditionNotFoundError`](#editionnotfounderror) indicates that the requested
  edition, or an edition referenced in one of its parents, could not be found.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `editions/listDefinedComponents`

Lists all the component groups defined in an edition.

#### Parameters

```typescript
interface EditionsListDefinedComponentsParameters {
  edition: EditionReference;
}
```

#### Result

```typescript
interface EditionsListDefinedComponentsResult {
  availableComponents: LibraryComponentGroup[];
}
```

#### Errors

- [`EditionNotFoundError`](#editionnotfounderror) indicates that the requested
  edition, or an edition referenced in one of its parents, could not be found.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `library/listLocal`

Lists all local libraries available in the system.

#### Parameters

```typescript
interface LibraryListLocalParameters {}
```

#### Result

```typescript
interface LibraryListLocalResult {
  localLibraries: LibraryEntry[];
}
```

#### Errors

- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `library/create`

Creates a new project package, but placed not in the projects directory, but in
the local libraries directory, so that other projects (including the current
one) that can resolve local libraries will be able to import it.

The created library package inherits all edition settings from the current
project.

The endpoint just returns an empty message at success. Once this operation
finishes, the IDE can add the import `import <namespace>.<name>` to the open
file which will import the newly created library (the IDE must also ensure that
`prefer-local-libraries` is set to `true` or otherwise, it must add a proper
override to its own edition file to see this local library). Once the import is
added, the library will be loaded and its content root will be sent in a
[`file/rootAdded`](#filerootadded) notification.

#### Parameters

```typescript
interface LibraryCreateParameters {
  namespace: string;
  name: string;
  authors: Contact[];
  maintainers: Contact[];
  license: string;
}
```

#### Result

```typescript
type LibraryCreateResult = null;
```

#### Errors

- [`InvalidLibraryName`](#invalidlibraryname) to signal that the selected
  library name is not valid.
- [`LibraryAlreadyExists`](#libraryalreadyexists) to signal that a library with
  the given namespace and name already exists.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `library/getMetadata`

Gets metadata associated with a specific library version.

If the version is `LocalLibraryVersion`, it will try to read the manifest file
of the local library and return an empty result if the manifest does not exist.

If the version is `PublishedLibraryVersion`, it will fetch the manifest from the
library repository. A cached manifest may also be used, if it is available.

All returned fields are optional, as they may be missing.

#### Parameters

```typescript
interface LibraryGetMetadataParameters {
  namespace: string;
  name: string;
  version: LibraryVersion;
}
```

#### Results

```typescript
interface LibraryGetMetadataResult {
  description?: string;
  tagLine?: string;
}
```

#### Errors

- [`LocalLibraryNotFound`](#locallibrarynotfound) to signal that a local library
  with the given name does not exist on the local libraries path.
- [`InvalidSemverVersion`](#invalidsemverversion) to signal that the provided
  version string is not a valid semver version.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `library/setMetadata`

Sets metadata associated with a local library that will be used for publishing.

All metadata fields are optional. If a field is not set in the parameters, it
will be removed from the metadata (if it was present before).

#### Parameters

```typescript
interface LibrarySetMetadataParameters {
  namespace: string;
  name: string;
  description?: string;
  tagLine?: string;
}
```

#### Results

```typescript
type LibrarySetMetadataResult = null;
```

#### Errors

- [`LocalLibraryNotFound`](#locallibrarynotfound) to signal that a local library
  with the given name does not exist on the local libraries path.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `library/getPackage`

Gets the package config associated with a specific library version.

If the version is `LocalLibraryVersion`, it will try to read the package file of
the local library and return an empty result if the manifest does not exist.

If the version is `PublishedLibraryVersion`, it will fetch the package config
from the library repository. A cached package config may also be used, if it is
available.

All returned fields are optional, as they may be missing.

#### Parameters

```typescript
interface LibraryGetPackageParameters {
  namespace: string;
  name: string;
  version: LibraryVersion;
}
```

#### Results

```typescript
interface LibraryGetPackageResult {
  license?: string;
  componentGroups?: LibraryComponentGroups;
}
```

#### Errors

- [`LocalLibraryNotFound`](#locallibrarynotfound) to signal that a local library
  with the given name does not exist on the local libraries path.
- [`InvalidSemverVersion`](#invalidsemverversion) to signal that the provided
  version string is not a valid semver version.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `library/publish`

Publishes a library located in the local libraries directory to the main Enso
library repository.

If `bumpVersionAfterPublish` is set to true, after publishing the library, its
version is bumped automatically, so that future publications will not clash
versions. This is a temporary solution and in the longer-term it should be
replaced with separate settings allowing to arbitrarily modify the library
version from the IDE.

The `uploadUrl` is the URL of the library repository that accepts library
uploads.

The metadata for publishing the library can be set with
[`library/setMetadata`](#librarysetmetadata). If it was not set, the publish
operation will still proceed, but that metadata will be missing.

#### Parameters

```typescript
interface LibraryPublishParameters {
  namespace: string;
  name: string;
  authToken: string;
  uploadUrl: string;
  bumpVersionAfterPublish?: boolean;
}
```

#### Result

```typescript
type LibraryPublishResult = null;
```

#### Errors

- [`LocalLibraryNotFound`](#locallibrarynotfound) to signal that a local library
  with the given name does not exist on the local libraries path.
- [`LibraryPublishError`](#librarypublisherror) to signal that the server did
  not accept to publish the library (for example because a library with the same
  version already exists).
- [`LibraryRepositoryAuthenticationError`](#libraryrepositoryauthenticationerror)
  to signal an authentication failure.
- [`LibraryUploadError`](#libraryuploaderror) to signal that the upload
  operation has failed, for network-related reasons.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

### `library/preinstall`

Ensures that the requested library and all of its (transitive) dependencies, at
versions as resolved by the current project's edition, are downloaded.

Once this operation completes, the library import can be added to the project
and it is guaranteed that (unless there were unexpected changes to the system,
like files being manually deleted) no further downloads will be needed, so that
the import should load quickly.

This can be used by the IDE to predownload the library that is about to be added
to a project, to avoid freezing the compiler for the time the dependencies are
being downloaded.

While this operation is in progress, multiple series of `task/*` notifications
may be sent, indicating progress of particular components being downloaded and
installed.

#### Parameters

```typescript
interface LibraryPreinstallParameters {
  namespace: string;
  name: string;
}
```

#### Result

```typescript
type LibraryPreinstallResult = null;
```

#### Errors

- [`LibraryNotResolved`](#librarynotresolved) to signal that the requested
  library or one of its dependencies could not be resolved.
- [`DependencyDiscoveryError`](#dependencydiscoveryerror) to signal that
  dependencies of the library could not be established.
- [`LibraryDownloadError`](#librarydownloaderror) to signal that the download
  operation has failed, for network-related reasons, or because the library was
  missing in the repository. The error includes the name and version of the
  library that failed to download - as when preinstalling a specific library the
  failure may be tied not to that library itself but also one of its
  dependencies.
- [`FileSystemError`](#filesystemerror) to signal a generic, unrecoverable
  file-system error.

## Runtime Operations

### `runtime/getComponentGroups`

Sent from the client to the server to get the list of component groups available
in runtime.

The engine is started with an empty list of libraries loaded. It means that the
request should be sent after the first
[`executionContext/executionComplete`](#executioncontextexecutioncomplete)
notification indicating that all the libraries are loaded, and the component
group list is populated. If the request is sent before the first notification,
the response may be empty or not contain all available components.

- **Type:** Request
- **Direction:** Client -> Server
- **Connection:** Protocol
- **Visibility:** Public

#### Parameters

```typescript
interface RuntimeGetComponentGroupsParameters {}
```

#### Result

```typescript
interface RuntimeGetComponentGroupsResult {
  componentGroups: LibraryComponentGroup[];
}
```

#### Errors

None

## Errors

The language server component also has its own set of errors. This section is
not a complete specification and will be updated as new errors are added.

Besides the required `code` and `message` fields, the errors may have a `data`
field which can store additional error-specific payload.

### `Error`

An error container for the binary connection that contains a code, message and
payload.

```csharp
namespace org.enso.languageserver.protocol.binary;

table Error {
  // A unique error code identifying error type.
  code: int;
  // An error message.
  message: string (required);
  // Additional payloads for the error.
  data : ErrorPayload;
}

union ErrorPayload {
  ...
}
```

Note:

- The union `ErrorPayload` will be extended with additional payloads as
  necessary.
- All textual-protocol errors can be represented using this structure.

### `AccessDeniedError`

It signals that a user doesn't have access to a resource.

```typescript
"error" : {
  "code" : 100,
  "message" : "Access denied"
}
```

### `FileSystemError`

This error signals generic file system errors.

```typescript
"error" : {
  "code" : 1000,
  "message" : string
}
```

### `ContentRootNotFoundError`

The error informs that the requested content root cannot be found.

```typescript
"error" : {
  "code" : 1001,
  "message" : "Content root not found"
}
```

### `FileNotFound`

It signals that requested file doesn't exist.

```typescript
"error" : {
  "code" : 1003,
  "message" : "File not found"
}
```

### `FileExists`

It signals that file already exists.

```typescript
"error" : {
  "code" : 1004,
  "message" : "File already exists"
}
```

### `OperationTimeoutError`

It signals that IO operation timed out.

```typescript
"error" : {
  "code" : 1005,
  "message" : "IO operation timeout"
}
```

### `NotDirectory`

It signals that provided path is not a directory.

```typescript
"error" : {
  "code" : 1006,
  "message" : "Path is not a directory"
}
```

### `NotFile`

It signals that the provided path is not a file.

```typescript
"error" : {
  "code" : 1007,
  "message" : "Path is not a file"
}
```

### `CannotOverwrite`

Signals that a streaming file write cannot overwrite a portion of the requested
file.

```typescript
"error" : {
  "code" : 1008,
  "message" : "Cannot overwrite the file without `overwriteExisting` set"
}
```

### `ReadOutOfBounds`

Signals that the requested file read was out of bounds for the file's size.

```typescript
"error" : {
  "code" : 1009
  "message" : "Read is out of bounds for the file"
  "payload" : {
    "fileLength" : 0
  }
}
```

### `CannotDecode`

Signals that the project configuration cannot be decoded.

```typescript
"error" : {
  "code" : 1010
  "message" : "Cannot decode the project configuration"
}
```

```csharp
namespace org.enso.languageserver.protocol.binary;

table ReadOutOfBoundsError {
  // The actual length of the file.
  fileLength : ulong (required);
}
```

### `StackItemNotFoundError`

It signals that provided stack item was not found.

```typescript
"error" : {
  "code" : 2001,
  "message" : "Stack item not found"
}

```

### `ContextNotFoundError`

It signals that provided context was not found.

```typescript
"error" : {
  "code" : 2002,
  "message" : "Context not found"
}
```

### `EmptyStackError`

It signals that stack is empty.

```typescript
"error" : {
  "code" : 2003,
  "message" : "Stack is empty"
}
```

### `InvalidStackItemError`

It signals that stack is invalid in this context.

```typescript
"error" : {
  "code" : 2004,
  "message" : "Invalid stack item"
}
```

### `ModuleNotFoundError`

It signals that the given module cannot be found.

```typescript
"error" : {
  "code" : 2005,
  "message" : "Module not found [Foo.Bar.Baz]"
}
```

### `VisualizationNotFoundError`

It signals that the visualization cannot be found.

```typescript
"error" : {
  "code" : 2006,
  "message" : "Visualization not found"
}
```

### `VisualizationExpressionError`

It signals that the expression specified in the `VisualizationConfiguration`
cannot be evaluated. The error contains an optional `data` field of type
[`Diagnostic`](#diagnostic) providing error details.

```typescript
"error" : {
  "code" : 2007,
  "message" : "Evaluation of the visualization expression failed [i is not defined]"
  "payload" : {
    "kind" : "Error",
    "message" : "i is not defined",
    "path" : null,
    "location" : {
      "start" : {
        "line" : 0,
        "character" : 8
      },
      "end" : {
        "line" : 0,
        "character" : 9
      }
    },
    "expressionId" : "aa1f75c4-8c4d-493d-a6a7-72123a52f084",
    "stack" : []
  }
}
```

### `FileNotOpenedError`

Signals that a file wasn't opened.

```typescript
"error" : {
  "code" : 3001,
  "message" : "File not opened"
}
```

### `TextEditValidationError`

Signals that validation has failed for a series of edits.

```typescript
"error" : {
  "code" : 3002,
  "message" : "The start position is after the end position"
}
```

### `InvalidVersionError`

Signals that version provided by a client doesn't match to the version computed
by the server.

```typescript
"error" : {
  "code" : 3003,
  "message" : "Invalid version [client version: ade2967cab172183d1a67ea40cb8e92e23218764bc9934c3795fcea5, server version: 7602967cab172183d1a67ea40cb8e92e23218764bc9934c3795fcea5]"
}
```

### `WriteDeniedError`

Signals that the client doesn't hold write lock to the buffer.

```typescript
"error" : {
  "code" : 3004,
  "message" : "Write denied"
}
```

### `CapabilityNotAcquired`

Signals that requested capability is not acquired.

```typescript
"error" : {
  "code" : 5001,
  "message" : "Capability not acquired"
}
```

### `SessionNotInitialisedError`

Signals that the request cannot be proccessed, because the session is not
initialized.

```typescript
"error" : {
  "code" : 6001,
  "message" : "Session not initialised"
}
```

### `SessionAlreadyInitialisedError`

Signals that the session is already initialized.

```typescript
"error" : {
  "code" : 6002,
  "message" : "Session already initialised"
}
```

### `ResourcesInitializationError`

Signals about an error in the Language Server initialization process.

```typescript
"error" : {
  "code" : 6003,
  "message" : "Failed to initialize the Language Server resources"
}
```

### `SuggestionsDatabaseError`

Signals about an error accessing the suggestions database.

```typescript
"error" : {
  "code" : 7001,
  "message" : "Suggestions database error"
}
```

### `ProjectNotFoundError`

Signals that the project could not be found in the root directory.

```typescript
"error" : {
  "code" : 7002,
  "message" : "Project not found in the root directory"
}
```

### `ModuleNameNotResolvedError`

Signals that the module name could not be resolved for the given file.

```typescript
"error" : {
  "code" : 7003,
  "message" : "Module name can't be resolved for the given file"
}
```

### `SuggestionNotFoundError`

Signals that the requested suggestion could not be found.

```typescript
"error" : {
  "code" : 7004,
  "message" : "Requested suggestion was not found"
}
```

### `EditionNotFoundError`

Signals that an edition could not be found.

Its payload includes the name of the edition that could not be found.

```typescript
"error" : {
  "code" : 8001,
  "message" : "Edition [<name>] could not be found.",
  "payload" : {
    "editionName": "<name>"
  }
}
```

### `LibraryAlreadyExists`

Signals that a local library with the specified namespace and name combination
already exists, so it cannot be created again.

```typescript
"error" : {
  "code" : 8002,
  "message" : "Library [<namespace>.<name>] already exists."
}
```

### `LibraryRepositoryAuthenticationError`

Signals that authentication to the library repository was declined.

```typescript
"error" : {
  "code" : 8003,
  "message" : "Authentication failed: [message]"
}
```

### `LibraryPublishError`

Signals that a request to the library repository failed.

```typescript
"error" : {
  "code" : 8004,
  "message" : "Could not publish the library: [message]"
}
```

### `LibraryUploadError`

Signals that uploading the library failed for network-related reasons.

```typescript
"error" : {
  "code" : 8005,
  "message" : "Could not upload the library: [message]"
}
```

### `LibraryDownloadError`

Signals that downloading the library failed for network-related reasons or that
it was not available in the repository.

```typescript
"error" : {
  "code" : 8006,
  "message" : "Could not download the library: [message]",
  "payload" : {
    "namespace" : "<namespace>",
    "name" : "<name>",
    "version": "<version>"
  }
}
```

### `LocalLibraryNotFound`

Signals that a local library with the specified namespace and name combination
was not found on the local libraries path.

```typescript
"error" : {
  "code" : 8007,
  "message" : "Local library [<namespace>.<name>] has not been found."
}
```

### `LibraryNotResolved`

Signals that a library could not be resolved - it was not defined in the edition
and the settings did not allow to resolve local libraries or it did not exist
there either.

```typescript
"error" : {
  "code" : 8008,
  "message" : "Could not resolve [<namespace>.<name>].",
  "payload" : {
    "namespace" : "<namespace>",
    "name" : "<name>"
  }
}
```

### `InvalidLibraryName`

Signals that the chosen library name is invalid.

It contains a suggestion of a similar name that is valid.

For example for `FooBar` it will suggest `Foo_Bar`.

```typescript
"error" : {
  "code" : 8009,
  "message" : "[<name>] is not a valid name: <reason>.",
  "payload" : {
    "suggestedName" : "<fixed-name>"
  }
}
```

### `DependencyDiscoveryError`

Signals that the library preinstall endpoint could not properly find
dependencies of the requested library.

```typescript
"error" : {
  "code" : 8010,
  "message" : "Error occurred while discovering dependencies: <reason>."
}
```

### `InvalidSemverVersion`

Signals that the provided version string is not a valid semver version. The
message contains the invalid version in the payload.

```typescript
"error" : {
  "code" : 8011,
  "message" : "[<invalid-version>] is not a valid semver version.",
  "payload" : {
    "version" : "<invalid-version>"
  }
}
```

### `ExpressionNotFoundError`

Signals that the expression cannot be found by the provided id.

```typescript
"error" : {
  "code" : 9001,
  "message" : "Expression not found by id [<expression-id>]"
}
```

### `FailedToApplyEdits`

Signals that the refactoring operation was not able to apply generated edits.

```typescript
"error" : {
  "code" : 9002,
  "message" : "Failed to apply edits to module [<module-name>]"
}
```

### `RefactoringNotSupported`

Signals that the refactoring of the given expression is not supported.

```typescript
"error" : {
  "code" : 9003,
  "message" : "Refactoring not supported for expression [<expression-id>]"
}
```
